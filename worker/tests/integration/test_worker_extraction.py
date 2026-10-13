"""The worker against real Kafka and RabbitMQ (Testcontainers), with a fake LLM and fake storage.

Every case runs on both brokers: the adapters must behave the same behind `MessageBroker`.
"""

import asyncio
import contextlib
import json
from collections.abc import AsyncIterator, Callable, Iterator
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import pytest
import pytest_asyncio
from jsonschema import Draft202012Validator
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
from probes import KafkaProbe, Probe, RabbitProbe
from sample_data import SAMPLES_BY_KEY
from support import TRACEPARENT, FakeFileStore, FakeLlm, FakeQrReader, load_schema, received_envelope, truth

pytestmark = [pytest.mark.integration, pytest.mark.asyncio(loop_scope="module")]

SAMPLE = SAMPLES_BY_KEY["s4"]
FILE_KEY = "documents/abc/nota.pdf"
QUIET_SECONDS = 6.0
# How late a retry may come back: broker round trip plus the consumer picking it up.
BACKOFF_SLACK_SECONDS = 4.0


def occurred_at(message: dict[str, Any]) -> datetime:
    return datetime.fromisoformat(message["occurredAt"])


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
    progress = await environment.probe.collect(QueueRole.PROGRESS, document_id, 2, wait=2)
    assert [message["payload"]["stage"] for message, _ in progress] == ["OCR"]


async def test_transient_failure_is_retried_with_backoff_until_it_succeeds(environment: Environment) -> None:
    llm = FakeLlm(extraction=truth(SAMPLE), error=TransientError("LLM 503 Service Unavailable"), failures=2)
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)
    document_id = envelope["documentId"]

    async with running(broker, consumer_for(broker, llm, render_pdf(SAMPLE)).handle):
        await send(environment, envelope)
        results = await environment.probe.collect(QueueRole.OUTPUT, document_id, 1)
        progress = [body for body, _ in await environment.probe.collect(QueueRole.PROGRESS, document_id, 8)]
        dead = await environment.probe.collect(QueueRole.DEAD_LETTER, document_id, 1, wait=2)

    assert len(results) == 1
    body, headers = results[0]
    assert body["attempt"] == 3
    assert body["requestId"] == envelope["requestId"]
    assert headers["traceparent"] == TRACEPARENT
    assert dead == []
    assert len(llm.received_text) == 3
    assert [(message["payload"]["stage"], message["attempt"]) for message in progress] == [
        ("OCR", 1),
        ("EXTRACTING", 1),
        ("RETRY_WAIT", 1),
        ("OCR", 2),
        ("EXTRACTING", 2),
        ("RETRY_WAIT", 2),
        ("OCR", 3),
        ("EXTRACTING", 3),
    ]
    # The wait announced is the wait observed: the next attempt starts no sooner than the backoff.
    for waiting, next_attempt in ((progress[2], progress[3]), (progress[5], progress[6])):
        announced = waiting["payload"]["retryInMs"] / 1000
        waited = (occurred_at(next_attempt) - occurred_at(waiting)).total_seconds()
        assert announced <= waited < announced + BACKOFF_SLACK_SECONDS
    assert [progress[2]["payload"]["retryInMs"], progress[5]["payload"]["retryInMs"]] == [1000, 2000]


async def test_fourth_transient_failure_goes_to_the_dead_letter_queue(environment: Environment) -> None:
    llm = FakeLlm(error=TransientError("LLM 503 Service Unavailable"))
    broker = environment.new_broker()
    envelope = received_envelope(environment.kind)
    document_id = envelope["documentId"]

    async with running(broker, consumer_for(broker, llm, render_pdf(SAMPLE)).handle):
        await send(environment, envelope)
        dead = await environment.probe.collect(QueueRole.DEAD_LETTER, document_id, 1)
        progress = [body for body, _ in await environment.probe.collect(QueueRole.PROGRESS, document_id, 11)]
        results = await environment.probe.collect(QueueRole.OUTPUT, document_id, 1, wait=2)

    assert len(dead) == 1
    body, headers = dead[0]
    assert body["eventType"] == "documento.retry"
    assert body["attempt"] == 4
    assert body["requestId"] == envelope["requestId"]
    assert headers["x-error"] == "LLM 503 Service Unavailable"
    assert headers["x-error-class"] == "TransientError"
    assert headers["x-attempts"] == "4"
    assert headers["traceparent"] == TRACEPARENT
    assert results == []
    assert len(llm.received_text) == 4
    waits = [message["payload"] for message in progress if message["payload"]["stage"] == "RETRY_WAIT"]
    assert [(wait["attempt"], wait["retryInMs"]) for wait in waits] == [(1, 1000), (2, 2000), (3, 4000)]


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
