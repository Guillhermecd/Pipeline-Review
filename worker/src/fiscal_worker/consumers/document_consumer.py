"""Entry point for a message, like a controller: validate the envelope, call the service, answer."""

import logging

from pydantic import ValidationError

from fiscal_worker.domain.errors import ExtractionError, PermanentError, TransientError
from fiscal_worker.domain.events import DocumentReceivedPayload, EventEnvelope, EventType, Stage
from fiscal_worker.messaging.protocols import IncomingMessage
from fiscal_worker.messaging.topology import retry_delay_ms
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService

log = logging.getLogger(__name__)

ACCEPTED_EVENT_TYPES = {EventType.DOCUMENT_RECEIVED, EventType.DOCUMENT_RETRY}
FIRST_ATTEMPT = 1


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
        try:
            envelope, received = _parse(incoming.body)
        except PermanentError as poison:
            log.warning("Poison message sent to the dead-letter queue: %s", poison.__cause__ or poison)
            await self._publisher.dead_letter(incoming, poison, FIRST_ATTEMPT, document_key=None)
            return

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
