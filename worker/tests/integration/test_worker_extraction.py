"""The worker against real Kafka and RabbitMQ (Testcontainers), with a fake LLM and fake storage.

Every case runs on both brokers: the adapters must behave the same behind `MessageBroker`.
"""

import asyncio
import contextlib
import json
from collections.abc import AsyncIterator, Callable, Iterator
from dataclasses import dataclass
from typing import Any

import pytest
import pytest_asyncio
from jsonschema import Draft202012Validator
from probes import KafkaProbe, Probe, RabbitProbe
from testcontainers.community.kafka import KafkaContainer
from testcontainers.community.rabbitmq import RabbitMqContainer

from fiscal_worker.consumers.document_consumer import DocumentConsumer
from fiscal_worker.domain.errors import TransientError
from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.extractors.paddle_ocr import PaddleOcrEngine
from fiscal_worker.extractors.text_reader import DocumentTextReader
from fiscal_worker.messaging.kafka import KafkaBroker
from fiscal_worker.messaging.protocols import IncomingMessage, MessageBroker, MessageHandler
from fiscal_worker.messaging.rabbitmq import RabbitMqBroker
from fiscal_worker.messaging.topology import QueueRole
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService
from generate_samples import render_pdf
from sample_data import SAMPLES_BY_KEY
from support import TRACEPARENT, FakeFileStore, FakeLlm, FakeQrReader, load_schema, received_envelope, truth

pytestmark = [pytest.mark.integration, pytest.mark.asyncio(loop_scope="module")]

SAMPLE = SAMPLES_BY_KEY["s4"]
FILE_KEY = "documents/abc/nota.pdf"
QUIET_SECONDS = 6.0


@dataclass
class Environment:
    kind: BrokerKind
    new_broker: Callable[[], MessageBroker]
    probe: Probe


@pytest.fixture(scope="module")
def kafka_container() -> Iterator[KafkaContainer]:
    with KafkaContainer().with_kraft() as container:
        yield container


@pytest.fixture(scope="module")
def rabbit_container() -> Iterator[RabbitMqContainer]:
    with RabbitMqContainer("rabbitmq:3-management") as container:
        yield container


@pytest_asyncio.fixture(scope="module", loop_scope="module", params=[BrokerKind.KAFKA, BrokerKind.RABBITMQ])
async def environment(request: pytest.FixtureRequest) -> AsyncIterator[Environment]:
    if request.param is BrokerKind.KAFKA:
        bootstrap = request.getfixturevalue("kafka_container").get_bootstrap_server()
        env = Environment(BrokerKind.KAFKA, lambda: KafkaBroker(bootstrap), KafkaProbe(bootstrap))
    else:
        container = request.getfixturevalue("rabbit_container")
        port = container.get_exposed_port(5672)
        url = f"amqp://guest:guest@{container.get_container_host_ip()}:{port}/"
        env = Environment(BrokerKind.RABBITMQ, lambda: RabbitMqBroker(url), RabbitProbe(url))
    yield env
    await env.probe.close()


def consumer_for(broker: MessageBroker, llm: FakeLlm, content: bytes) -> DocumentConsumer:
    service = ExtractionService(
        files=FakeFileStore(files={FILE_KEY: content}),
        text_reader=DocumentTextReader(PaddleOcrEngine("pt")),
        qr_reader=FakeQrReader(),
        llm=llm,
    )
    return DocumentConsumer(service, EventPublisher({broker.kind: broker}))


@contextlib.asynccontextmanager
async def running(broker: MessageBroker, handler: MessageHandler) -> AsyncIterator[None]:
    await broker.start()
    task = asyncio.create_task(broker.consume(handler))
    try:
        yield
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        await broker.stop()


async def send(env: Environment, envelope: dict[str, Any]) -> None:
    await env.probe.send(json.dumps(envelope).encode(), {"traceparent": TRACEPARENT})


async def test_extracts_a_document_end_to_end(environment: Environment) -> None:
    llm = FakeLlm(extraction=truth(SAMPLE))
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)
    document_id = envelope["documentId"]

    async with running(broker, consumer_for(broker, llm, render_pdf(SAMPLE)).handle):
        await send(environment, envelope)
        results = await environment.probe.collect(QueueRole.OUTPUT, document_id, 1)
        progress = await environment.probe.collect(QueueRole.PROGRESS, document_id, 2)

    assert len(results) == 1
    body, headers = results[0]
    Draft202012Validator(load_schema("envelope.schema.json")).validate(body)
    Draft202012Validator(load_schema("documento-extraido.payload.schema.json")).validate(body["payload"])
    assert body["requestId"] == envelope["requestId"]
    assert body["broker"] == environment.kind.value
    assert body["payload"]["fields"]["total"]["value"] == "283.80"
    assert headers["traceparent"] == TRACEPARENT
    assert [message["payload"]["stage"] for message, _ in progress] == ["OCR", "EXTRACTING"]
    assert await environment.probe.collect(QueueRole.DEAD_LETTER, document_id, 1, wait=2) == []


async def test_acknowledged_message_is_not_processed_again_after_a_restart(environment: Environment) -> None:
    llm = FakeLlm(extraction=truth(SAMPLE))
    envelope = received_envelope(environment.kind)
    first = environment.new_broker()
    async with running(first, consumer_for(first, llm, render_pdf(SAMPLE)).handle):
        await send(environment, envelope)
        assert len(await environment.probe.collect(QueueRole.OUTPUT, envelope["documentId"], 1)) == 1
    calls_before_restart = len(llm.received_text)

    second = environment.new_broker()
    async with running(second, consumer_for(second, llm, render_pdf(SAMPLE)).handle):
        await asyncio.sleep(QUIET_SECONDS)

    assert len(llm.received_text) == calls_before_restart


async def test_message_is_delivered_again_when_the_handler_fails(environment: Environment) -> None:
    """No acknowledgement before the result is confirmed: a crash mid-way must not lose the document."""
    llm = FakeLlm(extraction=truth(SAMPLE))
    broker = environment.new_broker()
    consumer = consumer_for(broker, llm, render_pdf(SAMPLE))
    envelope = received_envelope(environment.kind)
    deliveries = 0

    async def fails_once(incoming: IncomingMessage) -> None:
        nonlocal deliveries
        deliveries += 1
        if deliveries == 1:
            raise ConnectionError("simulated crash before the result was confirmed")
        await consumer.handle(incoming)

    async with running(broker, fails_once):
        await send(environment, envelope)
        results = await environment.probe.collect(QueueRole.OUTPUT, envelope["documentId"], 1)

    assert deliveries == 2
    assert len(results) == 1


async def test_permanent_failure_goes_straight_to_the_dead_letter_queue(environment: Environment) -> None:
    llm = FakeLlm(extraction=truth(SAMPLE))
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)
    document_id = envelope["documentId"]

    async with running(broker, consumer_for(broker, llm, b"%PDF-1.4 corrupted").handle):
        await send(environment, envelope)
        dead = await environment.probe.collect(QueueRole.DEAD_LETTER, document_id, 1)
        results = await environment.probe.collect(QueueRole.OUTPUT, document_id, 1, wait=2)

    assert len(dead) == 1
    body, headers = dead[0]
    assert body == envelope
    assert headers["x-error"] == "arquivo corrompido: PDF ilegível"
    assert headers["x-error-class"] == "PermanentError"
    assert headers["x-attempts"] == "1"
    assert headers["traceparent"] == TRACEPARENT
    assert results == []
    assert llm.received_text == []


async def test_transient_failure_is_dead_lettered_with_its_class(environment: Environment) -> None:
    """Until the retry topology exists (phase F3) a transient failure is parked, not dropped."""
    llm = FakeLlm(error=TransientError("LLM 503 Service Unavailable"))
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)

    async with running(broker, consumer_for(broker, llm, render_pdf(SAMPLE)).handle):
        await send(environment, envelope)
        dead = await environment.probe.collect(QueueRole.DEAD_LETTER, envelope["documentId"], 1)

    assert len(dead) == 1
    assert dead[0][1]["x-error"] == "LLM 503 Service Unavailable"
    assert dead[0][1]["x-error-class"] == "TransientError"


async def test_poison_message_is_dead_lettered_and_the_consumer_keeps_going(environment: Environment) -> None:
    llm = FakeLlm(extraction=truth(SAMPLE))
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)

    async with running(broker, consumer_for(broker, llm, render_pdf(SAMPLE)).handle):
        await environment.probe.send(b'{"documentId": "poison", "eventType": 42}', {})
        await send(environment, envelope)
        results = await environment.probe.collect(QueueRole.OUTPUT, envelope["documentId"], 1)
        poison = await environment.probe.collect(QueueRole.DEAD_LETTER, "poison", 1)

    assert len(results) == 1
    assert len(poison) == 1
    assert poison[0][1]["x-error"].startswith("mensagem fora do schema")
