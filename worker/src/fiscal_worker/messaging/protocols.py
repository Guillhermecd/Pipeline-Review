"""The broker as the worker sees it. Kafka and RabbitMQ adapters implement the same Protocol."""

from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from typing import Protocol

from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.messaging.topology import QueueRole

TRACEPARENT_HEADER = "traceparent"


@dataclass(frozen=True)
class IncomingMessage:
    body: bytes
    headers: Mapping[str, str]
    broker: BrokerKind


@dataclass(frozen=True)
class OutgoingMessage:
    key: str
    body: bytes
    headers: Mapping[str, str] = field(default_factory=dict)


MessageHandler = Callable[[IncomingMessage], Awaitable[None]]


class MessageBroker(Protocol):
    @property
    def kind(self) -> BrokerKind: ...

    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    async def publish(self, role: QueueRole, message: OutgoingMessage) -> None:
        """Returns only after the broker confirmed the write."""
        ...

    async def consume(self, handler: MessageHandler) -> None:
        """Runs until cancelled. A message is acknowledged only after `handler` returns; if it
        raises, the message is delivered again."""
        ...
