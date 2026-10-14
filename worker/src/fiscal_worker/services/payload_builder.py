"""Turns the LLM answer plus the document's text into the `documento.extraido` payload."""

from collections.abc import Callable
from statistics import fmean

from fiscal_worker.domain.extraction import (
    DocumentText,
    ExtractedField,
    ExtractedFields,
    ExtractedItem,
    ExtractionPayload,
    FieldSource,
    LlmItem,
    LlmResult,
    TokenUsage,
)
from fiscal_worker.helpers.cnpj import format_cnpj
from fiscal_worker.helpers.formats import date_variants, money_variants
from fiscal_worker.services.grounding import (
    MISSING,
    QR_CODE_CONFIDENCE,
    Matcher,
    Score,
    TextGrounding,
    matches_cnpj,
    matches_number,
    matches_words,
)

CONFIDENCE_DECIMALS = 4

MatcherFactory = Callable[[str], Matcher]


def build_payload(result: LlmResult, text: DocumentText, qr_cnpj: str | None) -> ExtractionPayload:
    grounding = TextGrounding(text)
    extraction = result.extraction
    return ExtractionPayload(
        document_kind=extraction.documentKind,
        fields=ExtractedFields(
            cnpj=_cnpj_field(extraction.cnpj, qr_cnpj, grounding),
            issuer=_field(extraction.issuer, grounding, matches_words),
            issue_date=_field(extraction.issueDate, grounding, lambda v: matches_number(date_variants(v))),
            total=_field(extraction.total, grounding, lambda v: matches_number(money_variants(v))),
        ),
        items=[
            ExtractedItem(description=item.description, quantity=item.quantity, unit_price=item.unitPrice)
            for item in extraction.items
        ],
        items_confidence=_items_confidence(extraction.items, grounding),
        ocr_engine=text.engine,
        llm_model=result.model,
        tokens=TokenUsage(input=result.input_tokens, output=result.output_tokens),
    )


def _cnpj_field(llm_value: str | None, qr_cnpj: str | None, grounding: TextGrounding) -> ExtractedField:
    """The QR Code wins: it is machine-readable data from the issuer, not a reading of printed text."""
    if qr_cnpj is not None:
        return ExtractedField(value=qr_cnpj, confidence=QR_CODE_CONFIDENCE, source=FieldSource.QR_CODE)
    formatted = format_cnpj(llm_value) if llm_value else None
    return _field(formatted, grounding, matches_cnpj)


def _field(value: str | None, grounding: TextGrounding, matcher_for: MatcherFactory) -> ExtractedField:
    if not value:
        return _to_field(None, MISSING)
    return _to_field(value, grounding.score(matcher_for(value)))


def _to_field(value: str | None, score: Score) -> ExtractedField:
    return ExtractedField(
        value=value, confidence=round(score.confidence, CONFIDENCE_DECIMALS), source=score.source
    )


def _items_confidence(items: list[LlmItem], grounding: TextGrounding) -> float:
    """Mean over the item lines. A line is as trustworthy as the weaker of its description and price."""
    if not items:
        return MISSING.confidence
    line_scores = [
        min(
            grounding.score(matches_words(item.description)).confidence,
            grounding.score(matches_number(money_variants(item.unitPrice))).confidence,
        )
        for item in items
    ]
    return round(fmean(line_scores), CONFIDENCE_DECIMALS)
