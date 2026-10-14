"""Entry point for a message, like a controller: validate the envelope, call the service, answer."""

import logging
from datetime import datetime

from opentelemetry.context import Context
from pydantic import ValidationError

from fiscal_worker.domain.errors import ExtractionError, PermanentError, TransientError
from fiscal_worker.domain.events import DocumentReceivedPayload, EventEnvelope, EventType, Stage
from fiscal_worker.messaging.protocols import IncomingMessage
from fiscal_worker.messaging.topology import (
    RETRY_BACKOFF_MS,
    QueueRole,
    queue_name,
    retry_delay_ms,
    retry_queue_label,
)
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService
from fiscal_worker.telemetry import tracing

log = logging.getLogger(__name__)

ACCEPTED_EVENT_TYPES = {EventType.DOCUMENT_RECEIVED, EventType.DOCUMENT_RETRY}
FIRST_ATTEMPT = 1
WORKER_NODE = "worker"
OCR_STAGE = "OCR"
MILLISECONDS = 1000


class DocumentConsumer:
    """Returning normally means "acknowledge": every outcome was already confirmed on a queue.

    A transient failure is parked for a retry while there are retries left (BACKEND.md §5.5).
    Everything else goes to the dead-letter queue with its class in the headers, so nothing is
    dropped or redelivered in a loop.
    """

    def __init__(self, service: ExtractionService, publisher: EventPublisher) -> None:
        self._service = service
        self._publisher = publisher

    async def handle(self, incoming: IncomingMessage) -> None:
        # Whoever sent the message is the parent, so the whole document stays in one trace.
        parent = tracing.extract(incoming.headers)
        try:
            envelope, received = _parse(incoming.body)
        except PermanentError as poison:
            log.warning("Poison message sent to the dead-letter queue: %s", poison.__cause__ or poison)
            await self._publisher.dead_letter(
                incoming, poison, FIRST_ATTEMPT, document_key=None, parent=parent
            )
            return

        _record_retry_wait(envelope, incoming, parent)
        with tracing.span(_consume_span_name(envelope, incoming), WORKER_NODE, OCR_STAGE, parent=parent):
            await self._process(incoming, envelope, received)

    async def _process(
        self, incoming: IncomingMessage, envelope: EventEnvelope, received: DocumentReceivedPayload
    ) -> None:
        async def announce(stage: Stage) -> None:
            await self._publisher.progress(envelope, incoming, stage)

        document_key = str(envelope.document_id)
        try:
            payload = await self._service.extract(received, announce)
        except ExtractionError as error:
            # The DLQ header carries the short text; the cause (provider message, SDK error) stays here.
            log.warning(
                "%s failed (%s): %s | cause: %r",
                envelope.document_code,
                type(error).__name__,
                error,
                error.__cause__,
            )
            delay_ms = retry_delay_ms(envelope.attempt) if isinstance(error, TransientError) else None
            if delay_ms is None:
                await self._publisher.dead_letter(incoming, error, envelope.attempt, document_key)
                return
            # Progress first: if the worker dies in between, the redelivery announces and parks again,
            # and the backend drops the repeated announcement.
            await self._publisher.retry_wait(envelope, incoming, error, delay_ms)
            await self._publisher.retry(envelope, incoming, delay_ms)
            return
        except Exception as error:
            log.exception("%s failed with an unexpected error", envelope.document_code)
            unexpected = PermanentError(f"erro inesperado no worker: {type(error).__name__}")
            await self._publisher.dead_letter(incoming, unexpected, envelope.attempt, document_key)
            return

        await self._publisher.extracted(envelope, incoming, payload)
        log.info("%s extracted (%s)", envelope.document_code, payload.ocr_engine)


def _consume_span_name(envelope: EventEnvelope, incoming: IncomingMessage) -> str:
    name = f"consume {queue_name(incoming.broker, QueueRole.INPUT)}"
    return name if envelope.attempt == FIRST_ATTEMPT else f"{name} · tentativa {envelope.attempt}"


def _record_retry_wait(envelope: EventEnvelope, incoming: IncomingMessage, parent: Context) -> None:
    """The time a retry spent parked becomes a span of its own, from the failure to this delivery."""
    if envelope.event_type is not EventType.DOCUMENT_RETRY:
        return
    failed_attempt = envelope.attempt - 1
    if not 1 <= failed_attempt <= len(RETRY_BACKOFF_MS):
        return
    seconds = RETRY_BACKOFF_MS[failed_attempt - 1] // MILLISECONDS
    tracing.record_wait(
        name=f"retry +{seconds}s → {retry_queue_label(incoming.broker)}",
        stage=f"Retry {failed_attempt}/{len(RETRY_BACKOFF_MS)}",
        # The retry message is written at the moment of the failure.
        started_at=datetime.fromisoformat(envelope.occurred_at),
        parent=parent,
        messaging=incoming.broker,
    )


def _parse(body: bytes) -> tuple[EventEnvelope, DocumentReceivedPayload]:
    """A message that does not fit the contract can never be processed: it is poison, not a retry."""
    try:
        envelope = EventEnvelope.model_validate_json(body)
        received = DocumentReceivedPayload.model_validate(envelope.payload)
    except ValidationError as error:
        raise PermanentError(f"mensagem fora do schema: {error.error_count()} erro(s)") from error
    if envelope.event_type not in ACCEPTED_EVENT_TYPES:
        raise PermanentError(
            f"mensagem fora do schema: eventType {envelope.event_type.value} não é de entrada"
        )
    return envelope, received
