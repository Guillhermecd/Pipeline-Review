"""Raw broker clients for tests and the smoke script: they play the API side (send input, read output)."""

import asyncio
import json
from typing import Any, Protocol
from uuid import uuid4

import aio_pika
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from fiscal_worker.messaging.topology import KAFKA_TOPICS, RABBIT_EXCHANGE, RABBIT_QUEUES, QueueRole

Received = tuple[dict[str, Any], dict[str, str]]
COLLECT_TIMEOUT_SECONDS = 40.0


class Probe(Protocol):
    async def send(self, body: bytes, headers: dict[str, str]) -> None: ...

    async def collect(
        self, role: QueueRole, document_id: str, count: int, wait: float = ...
    ) -> list[Received]:
        """Messages of one document on a queue: waits for `count`, or returns fewer on timeout."""
        ...

    async def close(self) -> None: ...


def _belongs_to(body: bytes, document_id: str) -> dict[str, Any] | None:
    try:
        parsed: dict[str, Any] = json.loads(body)
    except json.JSONDecodeError:
        return None
    return parsed if parsed.get("documentId") == document_id else None


class KafkaProbe:
    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer = AIOKafkaProducer(bootstrap_servers=bootstrap_servers)
        self._started = False

    async def send(self, body: bytes, headers: dict[str, str]) -> None:
        if not self._started:
            await self._producer.start()
            self._started = True
        kafka_headers = [(name, value.encode()) for name, value in headers.items()]
        await self._producer.send_and_wait(KAFKA_TOPICS[QueueRole.INPUT], value=body, headers=kafka_headers)

    async def collect(
        self, role: QueueRole, document_id: str, count: int, wait: float = COLLECT_TIMEOUT_SECONDS
    ) -> list[Received]:
        consumer = AIOKafkaConsumer(
            KAFKA_TOPICS[role],
            bootstrap_servers=self._bootstrap_servers,
            group_id=f"probe-{uuid4()}",
            auto_offset_reset="earliest",
        )
        await consumer.start()
        found: list[Received] = []
        try:
            async with asyncio.timeout(wait):
                async for record in consumer:
                    body = _belongs_to(record.value or b"", document_id)
                    if body is not None:
                        found.append((body, {name: value.decode() for name, value in record.headers}))
                    if len(found) >= count:
                        break
        except TimeoutError:
            pass
        finally:
            await consumer.stop()
        return found

    async def close(self) -> None:
        if self._started:
            await self._producer.stop()


class RabbitProbe:
    def __init__(self, url: str) -> None:
        self._url = url
        self._connection: Any = None

    async def _channel(self) -> Any:
        if self._connection is None:
            self._connection = await aio_pika.connect_robust(self._url)
        return await self._connection.channel()

    async def send(self, body: bytes, headers: dict[str, str]) -> None:
        channel = await self._channel()
        exchange = await channel.get_exchange(RABBIT_EXCHANGE)
        await exchange.publish(
            aio_pika.Message(body=body, headers=dict(headers)), routing_key=RABBIT_QUEUES[QueueRole.INPUT]
        )
        await channel.close()

    async def collect(
        self, role: QueueRole, document_id: str, count: int, wait: float = COLLECT_TIMEOUT_SECONDS
    ) -> list[Received]:
        channel = await self._channel()
        queue = await channel.get_queue(RABBIT_QUEUES[role])
        found: list[Received] = []
        deadline = asyncio.get_running_loop().time() + wait
        while len(found) < count and asyncio.get_running_loop().time() < deadline:
            delivery = await queue.get(fail=False)
            if delivery is None:
                await asyncio.sleep(0.2)
                continue
            await delivery.ack()
            body = _belongs_to(delivery.body, document_id)
            if body is not None:
                found.append((body, {name: str(value) for name, value in (delivery.headers or {}).items()}))
        await channel.close()
        return found

    async def close(self) -> None:
        if self._connection is not None:
            await self._connection.close()
