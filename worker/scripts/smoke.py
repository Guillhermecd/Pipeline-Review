"""End-to-end check: a real upload goes through the API, the broker and the worker.

Needs the compose services, the backend (:1337) and the worker running.

Usage (from worker/):  uv run python scripts/smoke.py [path/to/document]

The script only talks to the API, as the frontend does: it uploads, then follows the document's
status. The stored extraction is read from the database to be checked against the contract.
"""

import argparse
import json
import mimetypes
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
from jsonschema import Draft202012Validator

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DOCUMENT = (
    REPO_ROOT / "backend" / "src" / "main" / "resources" / "samples" / "nfce_farmacia_central.pdf"
)
PAYLOAD_SCHEMA = REPO_ROOT / "contracts" / "events" / "documento-extraido.payload.schema.json"
POSTGRES_CONTAINER = "pipeline-fiscal-postgres"
WAIT_SECONDS = 180.0
POLL_SECONDS = 0.5
IN_FLIGHT = {"RECEIVED", "QUEUED", "OCR", "EXTRACTING", "RETRY_WAIT"}
DEAD_LETTERED = "DEAD_LETTERED"


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("document", nargs="?", type=Path, default=DEFAULT_DOCUMENT)
    parser.add_argument("--api", default="http://localhost:1337")
    parser.add_argument("--email", default="admin@oaksd.local")
    parser.add_argument("--password", default="ChangeMe123!")
    return parser.parse_args()


def upload(client: httpx.Client, path: Path) -> dict[str, Any]:
    """Uploads the file. Trailing bytes make the content unique, so a rerun is not a 409.

    PDF readers ignore a comment after `%%EOF`, and image decoders ignore bytes after the end marker.
    """
    mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    content = path.read_bytes() + f"\n% smoke {uuid4()}\n".encode()
    response = client.post("/api/v1/documentos", files={"file": (path.name, content, mime_type)})
    if response.status_code != httpx.codes.ACCEPTED:
        sys.exit(f"Upload answered {response.status_code}: {response.text}")
    document: dict[str, Any] = response.json()
    return document


def follow(client: httpx.Client, document_id: str) -> dict[str, Any]:
    """Prints each stage the document goes through, until it leaves the worker's hands."""
    deadline = time.monotonic() + WAIT_SECONDS
    last_stage = None
    while True:
        document: dict[str, Any] = client.get(f"/api/v1/documentos/{document_id}").raise_for_status().json()
        stage = (document["status"], document["stage"]["label"])
        if stage != last_stage:
            error = f" · {document['lastError']}" if document["lastError"] else ""
            print(f"  {document['status']:<14} {document['stage']['label']}{error}")
            last_stage = stage
        if document["status"] not in IN_FLIGHT:
            return document
        if time.monotonic() > deadline:
            sys.exit(f"No outcome after {WAIT_SECONDS:.0f}s. Are the backend and the worker running?")
        time.sleep(POLL_SECONDS)


def stored_extraction(document_id: str) -> dict[str, Any]:
    query = (
        "SELECT raw_result::text FROM extractions "
        f"WHERE document_id = '{document_id}' ORDER BY created_at DESC LIMIT 1"
    )
    command = ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "postgres", "-d", "pipeline_fiscal", "-At"]
    row = subprocess.run([*command, "-c", query], capture_output=True, text=True, check=True).stdout.strip()
    payload: dict[str, Any] = json.loads(row)
    return payload


def main() -> int:
    arguments = parse_arguments()
    with httpx.Client(base_url=arguments.api, timeout=30) as client:
        login = client.post(
            "/api/auth/login", json={"email": arguments.email, "password": arguments.password}
        )
        login.raise_for_status()
        client.headers["Authorization"] = f"Bearer {login.json()['token']}"

        document = upload(client, arguments.document)
        print(f"Uploaded {arguments.document.name} as {document['code']} on {document['broker']}")
        document = follow(client, document["id"])

    if document["status"] == DEAD_LETTERED:
        print(f"Document went to the DLQ after {document['attempts']} attempt(s): {document['lastError']}")
        return 1

    payload = stored_extraction(document["id"])
    Draft202012Validator(json.loads(PAYLOAD_SCHEMA.read_text(encoding="utf-8"))).validate(payload)
    print("The stored extraction is valid against the contract:")
    print(json.dumps(payload, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
