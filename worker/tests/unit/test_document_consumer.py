import json
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from fiscal_worker.consumers.document_consumer import DocumentConsumer
from fiscal_worker.domain.errors import TransientError
from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.extractors.paddle_ocr import PaddleOcrEngine
from fiscal_worker.extractors.text_reader import DocumentTextReader
from fiscal_worker.messaging.protocols import IncomingMessage
from fiscal_worker.messaging.topology import QueueRole
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService
from generate_samples import render_pdf
from sample_data import SAMPLES_BY_KEY
from support import (
    TRACEPARENT,
    FakeFileStore,
    FakeLlm,
    FakeQrReader,
    RecordingBroker,
    load_schema,
    received_envelope,
    truth,
)

SAMPLE = SAMPLES_BY_KEY["s4"]
FILE_KEY = "documents/abc/nota.pdf"


class Harness:
    """Real consumer, publisher, service and PDF reader; fake storage, LLM and brokers."""

    def __init__(self, llm: FakeLlm | None = None, files: FakeFileStore | None = None) -> None:
        self.kafka = RecordingBroker(BrokerKind.KAFKA)
        self.rabbit = RecordingBroker(BrokerKind.RABBITMQ)
        self.llm = llm or FakeLlm(extraction=truth(SAMPLE))
        self.files = files or FakeFileStore(files={FILE_KEY: render_pdf(SAMPLE)})
        service = ExtractionService(
            files=self.files,
            # Text-layer PDFs never reach the OCR engine, so it is never loaded here.
            text_reader=DocumentTextReader(PaddleOcrEngine("pt")),
            qr_reader=FakeQrReader(),
            llm=self.llm,
        )
        brokers = {BrokerKind.KAFKA: self.kafka, BrokerKind.RABBITMQ: self.rabbit}
        self.consumer = DocumentConsumer(service, EventPublisher(brokers))

    async def deliver(self, body: bytes | dict[str, Any], broker: BrokerKind = BrokerKind.KAFKA) -> None:
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        await self.consumer.handle(
            IncomingMessage(body=raw, headers={"traceparent": TRACEPARENT}, broker=broker)
        )


async def test_publishes_progress_then_result_on_the_arrival_broker() -> None:
    harness = Harness()
    envelope = received_envelope()

    await harness.deliver(envelope)

    roles = [role for role, _ in harness.kafka.published]
    assert roles == [QueueRole.PROGRESS, QueueRole.PROGRESS, QueueRole.OUTPUT]
    assert harness.rabbit.published == []
    assert [body["payload"]["stage"] for body in harness.kafka.bodies(QueueRole.PROGRESS)] == [
        "OCR",
        "EXTRACTING",
    ]

    result = harness.kafka.bodies(QueueRole.OUTPUT)[0]
    Draft202012Validator(load_schema("envelope.schema.json")).validate(result)
    Draft202012Validator(load_schema("documento-extraido.payload.schema.json")).validate(result["payload"])
    assert result["eventType"] == "documento.extraido"
    assert result["payload"]["fields"]["total"] == {
        "value": "283.80",
        "confidence": 0.95,
        "source": "pdf_text",
    }
    assert result["payload"]["fields"]["cnpj"]["value"] == "60.701.190/0001-04"
    assert len(result["payload"]["items"]) == 3


async def test_every_outbound_message_keeps_the_identity_it_received() -> None:
    """The backend drops anything whose requestId is not the document's current one."""
    harness = Harness()
    envelope = received_envelope()

    await harness.deliver(envelope)

    event_ids = set()
    for _, message in harness.kafka.published:
        body = json.loads(message.body)
        for key in ("documentId", "documentCode", "idempotencyKey", "requestId", "attempt", "broker"):
            assert body[key] == envelope[key]
        assert message.headers == {"traceparent": TRACEPARENT}
        assert message.key == envelope["documentId"]
        event_ids.add(body["eventId"])
    assert len(event_ids) == 3
    assert envelope["eventId"] not in event_ids


async def test_answers_on_rabbitmq_when_the_message_came_from_rabbitmq() -> None:
    harness = Harness()

    await harness.deliver(received_envelope(BrokerKind.RABBITMQ), broker=BrokerKind.RABBITMQ)

    assert harness.kafka.published == []
    assert [role for role, _ in harness.rabbit.published][-1] is QueueRole.OUTPUT


async def test_accepts_a_retry_message() -> None:
    harness = Harness()

    await harness.deliver({**received_envelope(), "eventType": "documento.retry", "attempt": 2})

    assert harness.kafka.bodies(QueueRole.OUTPUT)[0]["attempt"] == 2


@pytest.mark.parametrize(
    "body",
    [
        b"not json",
        json.dumps({"eventType": "documento.recebido"}).encode(),
        json.dumps({**received_envelope(), "payload": {"fileName": "sem chave.pdf"}}).encode(),
        json.dumps({**received_envelope(), "eventType": "documento.extraido"}).encode(),
    ],
)
async def test_poison_message_goes_to_the_dead_letter_queue_untouched(body: bytes) -> None:
    harness = Harness()

    await harness.deliver(body)

    assert [role for role, _ in harness.kafka.published] == [QueueRole.DEAD_LETTER]
    _, message = harness.kafka.published[0]
    assert message.body == body
    assert message.headers["x-error"].startswith("mensagem fora do schema")
    assert message.headers["x-error-class"] == "PermanentError"
    assert message.headers["x-attempts"] == "1"
    assert message.headers["traceparent"] == TRACEPARENT
    assert harness.llm.received_text == []


async def test_transient_failure_is_dead_lettered_with_its_class() -> None:
    harness = Harness(llm=FakeLlm(error=TransientError("LLM 503 Service Unavailable")))
    envelope = {**received_envelope(), "attempt": 3}

    await harness.deliver(envelope)

    roles = [role for role, _ in harness.kafka.published]
    assert roles == [QueueRole.PROGRESS, QueueRole.PROGRESS, QueueRole.DEAD_LETTER]
    _, message = harness.kafka.published[-1]
    assert json.loads(message.body) == envelope
    assert message.headers["x-error"] == "LLM 503 Service Unavailable"
    assert message.headers["x-error-class"] == "TransientError"
    assert message.headers["x-attempts"] == "3"
    assert message.key == envelope["documentId"]


async def test_permanent_failure_is_dead_lettered_before_calling_the_llm() -> None:
    harness = Harness(files=FakeFileStore(files={FILE_KEY: b"%PDF-1.4 broken"}))

    await harness.deliver(received_envelope())

    _, message = harness.kafka.published[-1]
    assert [role for role, _ in harness.kafka.published] == [QueueRole.PROGRESS, QueueRole.DEAD_LETTER]
    assert message.headers["x-error-class"] == "PermanentError"
    assert message.headers["x-error"] == "arquivo corrompido: PDF ilegível"
    assert harness.llm.received_text == []


async def test_storage_failure_is_dead_lettered_as_transient() -> None:
    harness = Harness(files=FakeFileStore(error=TransientError("MinIO indisponível")))

    await harness.deliver(received_envelope())

    assert harness.kafka.published[-1][1].headers["x-error-class"] == "TransientError"


async def test_unexpected_error_is_dead_lettered_instead_of_looping() -> None:
    harness = Harness(llm=FakeLlm(error=ZeroDivisionError("bug")))

    await harness.deliver(received_envelope())

    _, message = harness.kafka.published[-1]
    assert message.headers["x-error"] == "erro inesperado no worker: ZeroDivisionError"
    assert message.headers["x-error-class"] == "PermanentError"


async def test_broker_failure_propagates_so_the_message_is_redelivered() -> None:
    """If the result cannot be confirmed, returning normally would acknowledge and lose it."""
    harness = Harness()
    harness.kafka.publish_error = ConnectionError("broker down")

    with pytest.raises(ConnectionError):
        await harness.deliver(received_envelope())
