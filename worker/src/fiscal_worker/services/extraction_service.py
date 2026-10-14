"""Orchestrates one extraction: file -> text -> LLM -> payload with per-field confidence."""

from collections.abc import Awaitable, Callable

from fiscal_worker.domain.errors import ExtractionError
from fiscal_worker.domain.events import DocumentReceivedPayload, Stage
from fiscal_worker.domain.extraction import DocumentFile, ExtractionPayload
from fiscal_worker.extractors.protocols import FileStore, LlmExtractor, QrCodeReader, TextReader
from fiscal_worker.helpers.cpf import mask_cpf
from fiscal_worker.services.payload_builder import build_payload
from fiscal_worker.telemetry import tracing

StageListener = Callable[[Stage], Awaitable[None]]

WORKER_NODE = "worker"
LLM_SPAN_NAME = "llm.extract (json schema)"


class ExtractionService:
    def __init__(
        self, files: FileStore, text_reader: TextReader, qr_reader: QrCodeReader, llm: LlmExtractor
    ) -> None:
        self._files = files
        self._text_reader = text_reader
        self._qr_reader = qr_reader
        self._llm = llm

    async def extract(self, received: DocumentReceivedPayload, on_stage: StageListener) -> ExtractionPayload:
        """Announces each stage before entering it, so the screens follow the document live."""
        await on_stage(Stage.OCR)
        with tracing.span("ocr", WORKER_NODE, "OCR") as reading:
            content = await self._files.download(received.file_key)
            file = DocumentFile(file_name=received.file_name, mime_type=received.mime_type, content=content)
            text = await self._text_reader.read(file)
            # Named after what actually read the text: `ocr.paddleocr` or `ocr.pdf_text`.
            reading.update_name(f"ocr.{text.engine}")
            qr_cnpj = await self._qr_reader.read_cnpj(file)

        await on_stage(Stage.EXTRACTING)
        with tracing.span(LLM_SPAN_NAME, WORKER_NODE, "Extração") as extracting:
            try:
                # Individuals' CPFs never leave for the provider (LGPD). The CNPJ is company data and stays.
                result = await self._llm.extract(mask_cpf(text.text))
            except ExtractionError as error:
                extracting.update_name(f"llm.extract → {error}")
                raise
        return build_payload(result, text, qr_cnpj)
