"""Kafka adapter (aiokafka): manual commits, confirmed sends."""

import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from aiokafka.errors import TopicAlreadyExistsError

from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.messaging.protocols import IncomingMessage, MessageHandler, OutgoingMessage
from fiscal_worker.messaging.topology import (
    KAFKA_CONSUMER_GROUP,
    KAFKA_PARTITIONS,
    KAFKA_REPLICATION_FACTOR,
    KAFKA_RETRY_TOPIC,
    KAFKA_TOPICS,
    QueueRole,
)

log = logging.getLogger(__name__)

REDELIVERY_DELAY_SECONDS = 2.0
# OCR on CPU can take tens of seconds; the default 5 minutes between polls is kept explicit.
MAX_POLL_INTERVAL_MS = 300_000


class KafkaBroker:
    kind = BrokerKind.KAFKA

    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer: AIOKafkaProducer | None = None
        self._consumer: AIOKafkaConsumer | None = None

    async def start(self) -> None:
        await self._ensure_topics()
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers, acks="all", enable_idempotence=True
        )
        await self._producer.start()
        self._consumer = AIOKafkaConsumer(
            KAFKA_TOPICS[QueueRole.INPUT],
            bootstrap_servers=self._bootstrap_servers,
            group_id=KAFKA_CONSUMER_GROUP,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_interval_ms=MAX_POLL_INTERVAL_MS,
        )
        await self._consumer.start()

    async def stop(self) -> None:
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()

    async def publish(self, role: QueueRole, message: OutgoingMessage) -> None:
        if self._producer is None:
            raise RuntimeError("KafkaBroker.start() was not called")
        headers = [(name, value.encode()) for name, value in message.headers.items()]
        await self._producer.send_and_wait(
            KAFKA_TOPICS[role], value=message.body, key=message.key.encode(), headers=headers
        )

    async def consume(self, handler: MessageHandler) -> None:
        if self._consumer is None:
            raise RuntimeError("KafkaBroker.start() was not called")
        async for record in self._consumer:
            partition = TopicPartition(record.topic, record.partition)
            headers = {name: value.decode(errors="replace") for name, value in record.headers}
            try:
                await handler(IncomingMessage(body=record.value or b"", headers=headers, broker=self.kind))
            except Exception:  # noqa: BLE001 - logged and redelivered, never dropped
                log.exception("Handler failed; offset %s of %s will be read again", record.offset, partition)
                self._consumer.seek(partition, record.offset)
                await asyncio.sleep(REDELIVERY_DELAY_SECONDS)
                continue
            # Offset moves only after the result is confirmed on the output topic.
            await self._consumer.commit({partition: record.offset + 1})

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
