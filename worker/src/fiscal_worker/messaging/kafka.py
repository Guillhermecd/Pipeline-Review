"""Kafka adapter (aiokafka): manual commits, confirmed sends, retry topic with a due time."""

import asyncio
import logging
import time
from collections.abc import Mapping

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, ConsumerRecord, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from aiokafka.errors import IllegalStateError, TopicAlreadyExistsError

from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.messaging.protocols import IncomingMessage, MessageHandler, OutgoingMessage
from fiscal_worker.messaging.topology import (
    KAFKA_CONSUMER_GROUP,
    KAFKA_NOT_BEFORE_HEADER,
    KAFKA_PARTITIONS,
    KAFKA_REPLICATION_FACTOR,
    KAFKA_RETRY_CONSUMER_GROUP,
    KAFKA_RETRY_TOPIC,
    KAFKA_TOPICS,
    QueueRole,
)

log = logging.getLogger(__name__)

REDELIVERY_DELAY_SECONDS = 2.0
# OCR on CPU can take tens of seconds; the default 5 minutes between polls is kept explicit.
MAX_POLL_INTERVAL_MS = 300_000
MILLISECONDS = 1000


class KafkaBroker:
    kind = BrokerKind.KAFKA

    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer: AIOKafkaProducer | None = None
        self._consumer: AIOKafkaConsumer | None = None
        self._retry_consumer: AIOKafkaConsumer | None = None

    async def start(self) -> None:
        await self._ensure_topics()
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers, acks="all", enable_idempotence=True
        )
        await self._producer.start()
        self._consumer = self._new_consumer(KAFKA_TOPICS[QueueRole.INPUT], KAFKA_CONSUMER_GROUP)
        await self._consumer.start()
        self._retry_consumer = self._new_consumer(KAFKA_RETRY_TOPIC, KAFKA_RETRY_CONSUMER_GROUP)
        await self._retry_consumer.start()

    async def stop(self) -> None:
        for client in (self._consumer, self._retry_consumer, self._producer):
            if client is not None:
                await client.stop()
        self._consumer = self._retry_consumer = self._producer = None

    async def publish(self, role: QueueRole, message: OutgoingMessage) -> None:
        await self._send(KAFKA_TOPICS[role], message.key.encode(), message.body, _encode(message.headers))

    async def publish_retry(self, message: OutgoingMessage, delay_ms: int) -> None:
        # Kafka has no per-message delay: the due time travels in a header and the relay waits for it.
        not_before = int(time.time() * MILLISECONDS) + delay_ms
        headers = {**message.headers, KAFKA_NOT_BEFORE_HEADER: str(not_before)}
        await self._send(KAFKA_RETRY_TOPIC, message.key.encode(), message.body, _encode(headers))

    async def consume(self, handler: MessageHandler) -> None:
        async with asyncio.TaskGroup() as tasks:
            tasks.create_task(self._consume_input(handler), name="kafka-input")
            tasks.create_task(self._relay_retries(), name="kafka-retry-relay")

    async def _consume_input(self, handler: MessageHandler) -> None:
        if self._consumer is None:
            raise RuntimeError("KafkaBroker.start() was not called")
        async for record in self._consumer:
            partition = TopicPartition(record.topic, record.partition)
            headers = {name: value.decode(errors="replace") for name, value in record.headers}
            try:
                await handler(IncomingMessage(body=record.value or b"", headers=headers, broker=self.kind))
            except Exception:
                log.exception("Handler failed; offset %s of %s will be read again", record.offset, partition)
                self._consumer.seek(partition, record.offset)
                await asyncio.sleep(REDELIVERY_DELAY_SECONDS)
                continue
            # Offset moves only after the result is confirmed on the output topic.
            await self._consumer.commit({partition: record.offset + 1})

    async def _relay_retries(self) -> None:
        """Moves each retry message back to the input topic once its due time has passed.

        A message that is not due pauses its partition instead of sleeping in the loop, so the
        other partitions keep flowing and the consumer keeps polling.
        """
        consumer = self._retry_consumer
        if consumer is None:
            raise RuntimeError("KafkaBroker.start() was not called")
        async for record in consumer:
            partition = TopicPartition(record.topic, record.partition)
            wait_seconds = _seconds_until_due(record)
            if wait_seconds > 0:
                consumer.pause(partition)
                consumer.seek(partition, record.offset)
                asyncio.get_running_loop().call_later(wait_seconds, _resume, consumer, partition)
                continue
            headers = [(name, value) for name, value in record.headers if name != KAFKA_NOT_BEFORE_HEADER]
            await self._send(KAFKA_TOPICS[QueueRole.INPUT], record.key, record.value or b"", headers)
            await consumer.commit({partition: record.offset + 1})

    async def _send(
        self, topic: str, key: bytes | None, body: bytes, headers: list[tuple[str, bytes]]
    ) -> None:
        if self._producer is None:
            raise RuntimeError("KafkaBroker.start() was not called")
        await self._producer.send_and_wait(topic, value=body, key=key, headers=headers)

    def _new_consumer(self, topic: str, group_id: str) -> AIOKafkaConsumer:
        return AIOKafkaConsumer(
            topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=group_id,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_interval_ms=MAX_POLL_INTERVAL_MS,
        )

    async def _ensure_topics(self) -> None:
        admin = AIOKafkaAdminClient(bootstrap_servers=self._bootstrap_servers)
        await admin.start()
        try:
            existing = set(await admin.list_topics())
            missing = [name for name in (*KAFKA_TOPICS.values(), KAFKA_RETRY_TOPIC) if name not in existing]
            if missing:
                await admin.create_topics(
                    [NewTopic(name, KAFKA_PARTITIONS, KAFKA_REPLICATION_FACTOR) for name in missing]
                )
        except TopicAlreadyExistsError:
            log.debug("Topics were created concurrently by another process")
        finally:
            await admin.close()


def _encode(headers: Mapping[str, str]) -> list[tuple[str, bytes]]:
    return [(name, value.encode()) for name, value in headers.items()]


def _seconds_until_due(record: ConsumerRecord[bytes, bytes]) -> float:
    """A message without a readable due time is due now: holding it forever would lose the document."""
    for name, value in record.headers:
        if name == KAFKA_NOT_BEFORE_HEADER:
            try:
                return int(value.decode()) / MILLISECONDS - time.time()
            except ValueError:
                return 0.0
    return 0.0


def _resume(consumer: AIOKafkaConsumer, partition: TopicPartition) -> None:
    try:
        consumer.resume(partition)
    except IllegalStateError:
        # The partition was reassigned while it waited; the new owner reads the message again.
        log.debug("Partition %s is no longer assigned; nothing to resume", partition)
