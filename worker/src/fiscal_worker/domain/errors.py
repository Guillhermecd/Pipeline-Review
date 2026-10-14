"""Failure classes. The class decides the message's destination, so every raise picks one."""


class ExtractionError(Exception):
    """Base for failures while processing a document. The text is shown in the DLQ screen."""


class TransientError(ExtractionError):
    """Worth retrying: LLM 429/5xx, timeout, storage unavailable."""


class PermanentError(ExtractionError):
    """Retrying cannot help: protected PDF, unsupported type, unreadable file, poison message."""
