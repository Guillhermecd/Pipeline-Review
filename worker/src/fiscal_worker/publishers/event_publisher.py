"""Builds and sends the worker's outbound messages: progress, result and dead letters."""

from collections.abc import Mapping
from typing import Any
from uuid import uuid4

from fiscal_worker.domain.errors import ExtractionError
from fiscal_worker.domain.events import BrokerKind, EventEnvelope, EventType, ProgressPayload, Stage
from fiscal_worker.domain.extraction import ExtractionPayload
from fiscal_worker.helpers.clock import iso_now
from fiscal_worker.messaging.protocols import TRACEPARENT_HEADER, IncomingMessage, MessageBroker, OutgoingMessage
from fiscal_worker.messaging.topology import QueueRole

ERROR_HEADER = "x-error"
ERROR_CLASS_HEADER = "x-error-class"
ATTEMPTS_HEADER = "x-attempts"
UNKNOWN_DOCUMENT_KEY = "unknown"


class EventPublisher:
    """A document's whole cycle stays on the broker it arrived on (BACKEND.md §5.1)."""

    def __init__(self, brokers: Mapping[BrokerKind, MessageBroker]) -> None:
        self._brokers = brokers

    async def progress(self, source: EventEnvelope, incoming: IncomingMessage, stage: Stage) -> None:
        payload = ProgressPayload(stage=stage, attempt=source.attempt)
        await self._send(QueueRole.PROGRESS, EventType.DOCUMENT_PROGRESS, source, incoming, payload.model_dump(
            mode="json", by_alias=True, exclude_none=True
        ))

    async def extracted(self, source: EventEnvelope, incoming: IncomingMessage, payload: ExtractionPayload) -> None:
        await self._send(
            QueueRole.OUTPUT,
            EventType.DOCUMENT_EXTRACTED,
            source,
            incoming,
            payload.model_dump(mode="json", by_alias=True),
        )

    async def dead_letter(
        self, incoming: IncomingMessage, error: ExtractionError, attempts: int, document_key: str | None
    ) -> None:
        """The original bytes go as they came, so even a message that failed to parse is preserved."""
        headers = {
            **incoming.headers,
            ERROR_HEADER: str(error),
            ERROR_CLASS_HEADER: type(error).__name__,
            ATTEMPTS_HEADER: str(attempts),
        }
        message = OutgoingMessage(key=document_key or UNKNOWN_DOCUMENT_KEY, body=incoming.body, headers=headers)
        await self._brokers[incoming.broker].publish(QueueRole.DEAD_LETTER, message)

    async def _send(
        self,
        role: QueueRole,
        event_type: EventType,
        source: EventEnvelope,
        incoming: IncomingMessage,
        payload: dict[str, Any],
    ) -> None:
        # Same requestId as received: the backend drops whatever does not match the current one.
        envelope = source.model_copy(
            update={"event_id": uuid4(), "event_type": event_type, "occurred_at": iso_now(), "payload": payload}
        )
        headers = {}
        if TRACEPARENT_HEADER in incoming.headers:
            headers[TRACEPARENT_HEADER] = incoming.headers[TRACEPARENT_HEADER]
        message = OutgoingMessage(
            key=str(source.document_id),
            body=envelope.model_dump_json(by_alias=True).encode(),
            headers=headers,
        )
        await self._brokers[incoming.broker].publish(role, message)
