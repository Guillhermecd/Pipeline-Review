"""Builds and sends the worker's outbound messages: progress, result, retries and dead letters."""

from collections.abc import Mapping
from typing import Any
from uuid import uuid4

from opentelemetry.context import Context

from fiscal_worker.domain.errors import ExtractionError
from fiscal_worker.domain.events import BrokerKind, EventEnvelope, EventType, ProgressPayload, Stage
from fiscal_worker.domain.extraction import ExtractionPayload
from fiscal_worker.helpers.clock import iso_now
from fiscal_worker.messaging.protocols import (
    TRACEPARENT_HEADER,
    IncomingMessage,
    MessageBroker,
    OutgoingMessage,
)
from fiscal_worker.messaging.topology import QueueRole, queue_name
from fiscal_worker.telemetry import tracing

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
        await self._send(
            QueueRole.PROGRESS,
            EventType.DOCUMENT_PROGRESS,
            source,
            incoming,
            payload.model_dump(mode="json", by_alias=True, exclude_none=True),
        )

    async def extracted(
        self, source: EventEnvelope, incoming: IncomingMessage, payload: ExtractionPayload
    ) -> None:
        name = f"publish {queue_name(incoming.broker, QueueRole.OUTPUT)}"
        with tracing.span(name, "broker", "Validação", messaging=incoming.broker):
            await self._send(
                QueueRole.OUTPUT,
                EventType.DOCUMENT_EXTRACTED,
                source,
                incoming,
                payload.model_dump(mode="json", by_alias=True),
            )

    async def retry_wait(
        self, source: EventEnvelope, incoming: IncomingMessage, error: ExtractionError, delay_ms: int
    ) -> None:
        payload = ProgressPayload(
            stage=Stage.RETRY_WAIT, attempt=source.attempt, error=str(error), retry_in_ms=delay_ms
        )
        await self._send(
            QueueRole.PROGRESS,
            EventType.DOCUMENT_PROGRESS,
            source,
            incoming,
            payload.model_dump(mode="json", by_alias=True, exclude_none=True),
        )

    async def retry(self, source: EventEnvelope, incoming: IncomingMessage, delay_ms: int) -> None:
        """Same document and requestId, next attempt: it comes back on the input queue after the wait."""
        envelope = source.model_copy(
            update={
                "event_id": uuid4(),
                "event_type": EventType.DOCUMENT_RETRY,
                "attempt": source.attempt + 1,
                "occurred_at": iso_now(),
            }
        )
        await self._brokers[incoming.broker].publish_retry(self._message(envelope, incoming), delay_ms)

    async def dead_letter(
        self,
        incoming: IncomingMessage,
        error: ExtractionError,
        attempts: int,
        document_key: str | None,
        parent: Context | None = None,
    ) -> None:
        """The original bytes go as they came, so even a message that failed to parse is preserved.

        `parent` is for a message rejected before any span was opened for it.
        """
        name = f"publish {queue_name(incoming.broker, QueueRole.DEAD_LETTER)}"
        with tracing.span(name, "retry", "DLQ", parent=parent, messaging=incoming.broker) as current:
            tracing.fail(current, str(error))
            headers = {
                **incoming.headers,
                **_trace_header(incoming),
                ERROR_HEADER: str(error),
                ERROR_CLASS_HEADER: type(error).__name__,
                ATTEMPTS_HEADER: str(attempts),
            }
            message = OutgoingMessage(
                key=document_key or UNKNOWN_DOCUMENT_KEY, body=incoming.body, headers=headers
            )
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
            update={
                "event_id": uuid4(),
                "event_type": event_type,
                "occurred_at": iso_now(),
                "payload": payload,
            }
        )
        await self._brokers[incoming.broker].publish(role, self._message(envelope, incoming))

    @staticmethod
    def _message(envelope: EventEnvelope, incoming: IncomingMessage) -> OutgoingMessage:
        """Only the trace context is carried over; broker bookkeeping headers stay behind."""
        return OutgoingMessage(
            key=str(envelope.document_id),
            body=envelope.model_dump_json(by_alias=True).encode(),
            headers=_trace_header(incoming),
        )


def _trace_header(incoming: IncomingMessage) -> dict[str, str]:
    """The receiver continues from the span that is sending; outside any span, from the sender's."""
    traceparent = tracing.current_traceparent() or incoming.headers.get(TRACEPARENT_HEADER)
    return {TRACEPARENT_HEADER: traceparent} if traceparent else {}
