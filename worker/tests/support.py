"""Shared test doubles and helpers."""

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from uuid import uuid4

from fiscal_worker.domain.errors import ExtractionError
from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.domain.extraction import (
    DocumentFile,
    DocumentText,
    ExtractionPayload,
    LlmExtraction,
    LlmItem,
    LlmResult,
)
from fiscal_worker.messaging.protocols import MessageHandler, OutgoingMessage
from fiscal_worker.messaging.topology import QueueRole
from sample_data import Sample

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"
FAKE_MODEL = "fake-llm"
TRACEPARENT = "00-58205aafa3ed1750e5ece79e8bde955d-57e53b06de0e6c94-01"

# Same weights as the backend's ConfidenceScorer (BACKEND.md §6.5).
SCORE_WEIGHTS = {"cnpj": 0.25, "total": 0.25, "items": 0.20, "issueDate": 0.15, "issuer": 0.15}


def load_schema(name: str) -> dict[str, Any]:
    schema: dict[str, Any] = json.loads((CONTRACTS / "events" / name).read_text(encoding="utf-8"))
    return schema


def load_example(name: str) -> dict[str, Any]:
    example: dict[str, Any] = json.loads((CONTRACTS / "examples" / name).read_text(encoding="utf-8"))
    return example


def weighted_score(payload: ExtractionPayload) -> float:
    fields = payload.fields
    return (
        fields.cnpj.confidence * SCORE_WEIGHTS["cnpj"]
        + fields.total.confidence * SCORE_WEIGHTS["total"]
        + payload.items_confidence * SCORE_WEIGHTS["items"]
        + fields.issue_date.confidence * SCORE_WEIGHTS["issueDate"]
        + fields.issuer.confidence * SCORE_WEIGHTS["issuer"]
    )


def truth(sample: Sample) -> LlmExtraction:
    """What a perfect LLM would answer for a sample: exactly what is printed on it."""
    return LlmExtraction(
        documentKind=sample.kind,  # type: ignore[arg-type]
        cnpj=sample.cnpj,
        issuer=sample.issuer,
        issueDate=sample.issue_date,
        total=f"{sample.total:.2f}",
        items=[
            LlmItem(
                description=item.description,
                quantity=format(item.quantity.normalize(), "f"),
                unitPrice=f"{item.unit_price:.2f}",
            )
            for item in sample.items
        ],
    )


def received_envelope(broker: BrokerKind = BrokerKind.KAFKA, **payload_overrides: str) -> dict[str, Any]:
    payload = {"fileKey": "documents/abc/nota.pdf", "mimeType": "application/pdf", "fileName": "nota.pdf"}
    payload.update(payload_overrides)
    return {
        "eventId": str(uuid4()),
        "eventType": "documento.recebido",
        "schemaVersion": 1,
        "documentId": str(uuid4()),
        "documentCode": "DOC-1007",
        "idempotencyKey": "sha256:" + "a" * 64,
        "requestId": str(uuid4()),
        "attempt": 1,
        "broker": broker.value,
        "occurredAt": "2026-10-13T13:20:00.000Z",
        "payload": payload,
    }


@dataclass
class FakeLlm:
    """Answers with a fixed extraction, or raises. Records the text it was given."""

    extraction: LlmExtraction | None = None
    error: Exception | None = None
    received_text: list[str] = field(default_factory=list)

    async def extract(self, document_text: str) -> LlmResult:
        self.received_text.append(document_text)
        if self.error is not None:
            raise self.error
        assert self.extraction is not None
        return LlmResult(extraction=self.extraction, model=FAKE_MODEL, input_tokens=120, output_tokens=40)


@dataclass
class FakeFileStore:
    files: dict[str, bytes] = field(default_factory=dict)
    error: ExtractionError | None = None

    async def download(self, key: str) -> bytes:
        if self.error is not None:
            raise self.error
        return self.files[key]


@dataclass
class FakeTextReader:
    text: DocumentText

    async def read(self, file: DocumentFile) -> DocumentText:
        return self.text


@dataclass
class FakeQrReader:
    cnpj: str | None = None

    async def read_cnpj(self, file: DocumentFile) -> str | None:
        return self.cnpj


@dataclass
class RecordingBroker:
    """In-memory broker: keeps what was published, in order."""

    kind: BrokerKind
    published: list[tuple[QueueRole, OutgoingMessage]] = field(default_factory=list)
    publish_error: Exception | None = None

    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    async def publish(self, role: QueueRole, message: OutgoingMessage) -> None:
        if self.publish_error is not None:
            raise self.publish_error
        self.published.append((role, message))

    async def consume(self, handler: MessageHandler) -> None: ...

    def bodies(self, role: QueueRole) -> list[dict[str, Any]]:
        return [
            json.loads(message.body) for published_role, message in self.published if published_role is role
        ]
