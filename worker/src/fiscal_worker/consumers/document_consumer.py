"""Entry point for a message, like a controller: validate the envelope, call the service, answer."""

import logging

from pydantic import ValidationError

from fiscal_worker.domain.errors import ExtractionError, PermanentError
from fiscal_worker.domain.events import DocumentReceivedPayload, EventEnvelope, EventType, Stage
from fiscal_worker.messaging.protocols import IncomingMessage
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService

log = logging.getLogger(__name__)

ACCEPTED_EVENT_TYPES = {EventType.DOCUMENT_RECEIVED, EventType.DOCUMENT_RETRY}
FIRST_ATTEMPT = 1


class DocumentConsumer:
    """Returning normally means "acknowledge": every outcome was already confirmed on a queue.

    Until the retry topology exists (phase F3), every failure goes straight to the dead-letter
    queue with its class in the headers, so nothing is dropped or redelivered in a loop.
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
            log.warning("%s failed (%s): %s", envelope.document_code, type(error).__name__, error)
            await self._publisher.dead_letter(incoming, error, envelope.attempt, document_key)
            return
        except Exception as error:  # noqa: BLE001 - a bug must not redeliver the same document forever
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
        raise PermanentError(f"mensagem fora do schema: eventType {envelope.event_type.value} não é de entrada")
    return envelope, received
