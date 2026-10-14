"""The extraction result (`documento.extraido` payload) and what the extractors hand to the service."""

import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from fiscal_worker.domain.events import ContractModel

DocumentKind = Literal["NF-e", "NFC-e", "NFS-e", "Recibo", "Cupom"]
OcrEngine = Literal["paddleocr", "pdf_text"]

DECIMAL_2 = r"^[0-9]+\.[0-9]{2}$"
QUANTITY = r"^[0-9]+(\.[0-9]{1,3})?$"
ISO_DATE = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"


class FieldSource(StrEnum):
    QR_CODE = "qr_code"
    PDF_TEXT = "pdf_text"
    OCR = "ocr"
    LLM = "llm"


class ExtractedField(ContractModel):
    value: str | None
    confidence: float = Field(ge=0, le=1)
    source: FieldSource


class ExtractedFields(ContractModel):
    cnpj: ExtractedField
    issuer: ExtractedField
    issue_date: ExtractedField
    total: ExtractedField

    @model_validator(mode="after")
    def _values_are_normalised(self) -> "ExtractedFields":
        """Dates and money travel in one format only, whatever the document printed."""
        checks = (("issueDate", self.issue_date, ISO_DATE), ("total", self.total, DECIMAL_2))
        for name, field, pattern in checks:
            if field.value is not None and not re.fullmatch(pattern, field.value):
                raise ValueError(f"{name} must match {pattern}")
        return self


class ExtractedItem(ContractModel):
    description: str = Field(min_length=1)
    quantity: str = Field(pattern=QUANTITY)
    unit_price: str = Field(pattern=DECIMAL_2)


class TokenUsage(ContractModel):
    input: int = Field(ge=0)
    output: int = Field(ge=0)


class ExtractionPayload(ContractModel):
    document_kind: DocumentKind
    fields: ExtractedFields
    items: list[ExtractedItem]
    items_confidence: float = Field(ge=0, le=1)
    ocr_engine: OcrEngine
    llm_model: str = Field(min_length=1)
    tokens: TokenUsage


class LlmItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    description: str = Field(min_length=1)
    quantity: str = Field(pattern=QUANTITY)
    unitPrice: str = Field(pattern=DECIMAL_2)  # noqa: N815 - mirrors the JSON Schema sent to the LLM


class LlmExtraction(BaseModel):
    """What the LLM must return. Field names are the JSON Schema's, so the answer parses directly."""

    model_config = ConfigDict(extra="forbid")

    documentKind: DocumentKind  # noqa: N815
    cnpj: str | None
    issuer: str | None
    issueDate: str | None = Field(pattern=ISO_DATE)  # noqa: N815
    total: str | None = Field(pattern=DECIMAL_2)
    items: list[LlmItem]


@dataclass(frozen=True)
class LlmResult:
    extraction: LlmExtraction
    model: str
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class TextLine:
    """One recognised line. PDF text layers have no recognition score, so `confidence` is None."""

    text: str
    confidence: float | None


@dataclass(frozen=True)
class DocumentText:
    engine: OcrEngine
    lines: tuple[TextLine, ...]

    @property
    def text(self) -> str:
        return "\n".join(line.text for line in self.lines)


@dataclass(frozen=True)
class DocumentFile:
    file_name: str
    mime_type: str
    content: bytes
