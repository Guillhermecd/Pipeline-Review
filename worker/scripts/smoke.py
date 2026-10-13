"""End-to-end check of phase F2: a real upload becomes a valid `documento.extraido`.

Needs the compose services, the backend (:1337) and the worker running.

Usage (from worker/):  uv run python scripts/smoke.py [path/to/document]

The outbox relay only exists from phase F3 on, so this script plays its part: it reads the
envelope the API stored in `outbox_events` and publishes it to the input queue. The worker
itself never touches the database.
"""

import argparse
import asyncio
import json
import mimetypes
import subprocess
import sys
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
from jsonschema import Draft202012Validator

from fiscal_worker.messaging.topology import QueueRole
from probes import KafkaProbe, Probe, RabbitProbe

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DOCUMENT = (
    REPO_ROOT / "backend" / "src" / "main" / "resources" / "samples" / "nfce_farmacia_central.pdf"
)
SCHEMAS = REPO_ROOT / "contracts" / "events"
POSTGRES_CONTAINER = "pipeline-fiscal-postgres"
WAIT_SECONDS = 180.0


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("document", nargs="?", type=Path, default=DEFAULT_DOCUMENT)
    parser.add_argument("--api", default="http://localhost:1337")
    parser.add_argument("--email", default="admin@oaksd.local")
    parser.add_argument("--password", default="ChangeMe123!")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--rabbitmq", default="amqp://guest:guest@localhost:5673/")
    return parser.parse_args()


def upload(arguments: argparse.Namespace) -> dict[str, Any]:
    """Uploads the file. A trailing comment makes the bytes unique, so a rerun is not a 409."""
    path: Path = arguments.document
    mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    content = path.read_bytes()
    if mime_type == "application/pdf":
        content += f"\n% smoke {uuid4()}\n".encode()
    with httpx.Client(base_url=arguments.api, timeout=30) as client:
        login = client.post(
            "/api/auth/login", json={"email": arguments.email, "password": arguments.password}
        )
        login.raise_for_status()
        response = client.post(
            "/api/v1/documentos",
            headers={"Authorization": f"Bearer {login.json()['token']}"},
            files={"file": (path.name, content, mime_type)},
        )
    if response.status_code != httpx.codes.ACCEPTED:
        sys.exit(f"Upload answered {response.status_code}: {response.text}")
    document: dict[str, Any] = response.json()
    return document


def read_outbox(document_id: str) -> tuple[dict[str, Any], str]:
    query = (
        "SELECT payload::text || E'\\t' || traceparent FROM outbox_events "
        f"WHERE document_id = '{document_id}'"
    )
    command = ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "postgres", "-d", "pipeline_fiscal", "-At"]
    row = subprocess.run([*command, "-c", query], capture_output=True, text=True, check=True).stdout.strip()
    payload, traceparent = row.rsplit("\t", 1)
    envelope: dict[str, Any] = json.loads(payload)
    return envelope, traceparent


def validate(body: dict[str, Any]) -> None:
    for schema_name, instance in (
        ("envelope.schema.json", body),
        ("documento-extraido.payload.schema.json", body["payload"]),
    ):
        schema = json.loads((SCHEMAS / schema_name).read_text(encoding="utf-8"))
        Draft202012Validator(schema).validate(instance)


async def await_outcome(probe: Probe, document_id: str) -> int:
    """Whichever comes first: the result on the output queue, or the document in the DLQ."""
    result = asyncio.create_task(probe.collect(QueueRole.OUTPUT, document_id, 1, WAIT_SECONDS))
    dead = asyncio.create_task(probe.collect(QueueRole.DEAD_LETTER, document_id, 1, WAIT_SECONDS))
    done, pending = await asyncio.wait({result, dead}, return_when=asyncio.FIRST_COMPLETED)
    first = done.pop()
    if not first.result():
        first = pending.pop()
        await first
    for task in pending:
        task.cancel()

    if first is result and result.result():
        body, headers = result.result()[0]
        validate(body)
        print("documento.extraido is valid against the contract:")
        print(json.dumps(body["payload"], indent=2, ensure_ascii=False))
        print(f"traceparent: {headers.get('traceparent')}")
        return 0
    if dead.done() and dead.result():
        _, headers = dead.result()[0]
        print(f"Document went to the DLQ: {headers.get('x-error-class')}: {headers.get('x-error')}")
        return 1
    print(f"No outcome after {WAIT_SECONDS:.0f}s. Is the worker running?")
    return 2


async def main() -> int:
    arguments = parse_arguments()
    document = upload(arguments)
    print(f"Uploaded {arguments.document.name} as {document['code']} ({document['id']})")
    envelope, traceparent = read_outbox(document["id"])
    probe: Probe = (
        KafkaProbe(arguments.kafka) if envelope["broker"] == "kafka" else RabbitProbe(arguments.rabbitmq)
    )
    try:
        await probe.send(json.dumps(envelope).encode(), {"traceparent": traceparent})
        print(f"Published {envelope['eventType']} to the {envelope['broker']} input queue")
        return await await_outcome(probe, document["id"])
    finally:
        await probe.close()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
