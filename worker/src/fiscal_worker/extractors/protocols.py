"""What the extraction service depends on. Implementations are injected, so tests swap in fakes."""

from typing import Protocol

from fiscal_worker.domain.extraction import DocumentFile, DocumentText, LlmResult


class FileStore(Protocol):
    async def download(self, key: str) -> bytes:
        """Raises TransientError when storage is unreachable, PermanentError when the object is gone."""
        ...


class TextReader(Protocol):
    async def read(self, file: DocumentFile) -> DocumentText:
        """Raises PermanentError for files that can never be read (protected, corrupt, unsupported)."""
        ...


class QrCodeReader(Protocol):
    async def read_cnpj(self, file: DocumentFile) -> str | None:
        """The issuer CNPJ from an NFC-e QR Code, or None when the document has none."""
        ...


class LlmExtractor(Protocol):
    async def extract(self, document_text: str) -> LlmResult:
        """Raises TransientError on 429/5xx/timeout, PermanentError when the answer stays off-schema."""
        ...
