"""RabbitMQ adapter (aio-pika): manual acks, publisher confirms."""

import asyncio
import logging

import aio_pika
from aio_pika.abc import AbstractExchange, AbstractQueue, AbstractRobustConnection

from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.messaging.protocols import IncomingMessage, MessageHandler, OutgoingMessage
from fiscal_worker.messaging.topology import (
    RABBIT_EXCHANGE,
    RABBIT_PREFETCH,
    RABBIT_QUEUES,
    RABBIT_RETRY_QUEUES,
    QueueRole,
    rabbit_retry_arguments,
)

log = logging.getLogger(__name__)

REDELIVERY_DELAY_SECONDS = 2.0


class RabbitMqBroker:
    kind = BrokerKind.RABBITMQ

    def __init__(self, url: str) -> None:
        self._url = url
        self._connection: AbstractRobustConnection | None = None
        self._exchange: AbstractExchange | None = None
        self._input_queue: AbstractQueue | None = None

    async def start(self) -> None:
        self._connection = await aio_pika.connect_robust(self._url)
        channel = await self._connection.channel(publisher_confirms=True)
        await channel.set_qos(prefetch_count=RABBIT_PREFETCH)
        self._exchange = await channel.declare_exchange(
            RABBIT_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True
        )
        for role, name in RABBIT_QUEUES.items():
            queue = await channel.declare_queue(name, durable=True)
            await queue.bind(self._exchange, routing_key=name)
            if role is QueueRole.INPUT:
                self._input_queue = queue
        for delay_ms, name in RABBIT_RETRY_QUEUES.items():
            retry_queue = await channel.declare_queue(
                name, durable=True, arguments=rabbit_retry_arguments(delay_ms)
            )
            await retry_queue.bind(self._exchange, routing_key=name)

    async def stop(self) -> None:
        if self._connection is not None:
            await self._connection.close()

    async def publish(self, role: QueueRole, message: OutgoingMessage) -> None:
        if self._exchange is None:
            raise RuntimeError("RabbitMqBroker.start() was not called")
        await self._exchange.publish(
            aio_pika.Message(
                body=message.body,
                headers=dict(message.headers),
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key=RABBIT_QUEUES[role],
            mandatory=True,
        )

    async def consume(self, handler: MessageHandler) -> None:
        if self._input_queue is None:
            raise RuntimeError("RabbitMqBroker.start() was not called")
        async with self._input_queue.iterator() as deliveries:
            async for delivery in deliveries:
                headers = {name: str(value) for name, value in (delivery.headers or {}).items()}
                try:
                    await handler(IncomingMessage(body=delivery.body, headers=headers, broker=self.kind))
                except Exception:
                    log.exception("Handler failed; the message goes back to the queue")
                    await asyncio.sleep(REDELIVERY_DELAY_SECONDS)
                    await delivery.nack(requeue=True)
                    continue
                # Ack only after the result is confirmed on the output queue.
                await delivery.ack()
