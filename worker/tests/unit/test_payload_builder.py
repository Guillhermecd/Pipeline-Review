import pytest

from fiscal_worker.domain.extraction import (
    DocumentText,
    FieldSource,
    LlmExtraction,
    LlmItem,
    LlmResult,
    TextLine,
)
from fiscal_worker.services.payload_builder import build_payload
from support import FAKE_MODEL

PDF_TEXT = DocumentText(
    engine="pdf_text",
    lines=tuple(
        TextLine(line, None)
        for line in (
            "Emitente: Papelaria Ponto Final",
            "CNPJ: 60.701.190/0001-04",
            "Data de emissão: 28/09/2026",
            "Resma A4 75g",
            "5",
            "29,90",
            "149,50",
            "VALOR TOTAL R$ 1.283,80",
        )
    ),
)


def result(**overrides: object) -> LlmResult:
    values: dict[str, object] = {
        "documentKind": "NF-e",
        "cnpj": "60.701.190/0001-04",
        "issuer": "Papelaria Ponto Final",
        "issueDate": "2026-09-28",
        "total": "1283.80",
        "items": [LlmItem(description="Resma A4 75g", quantity="5", unitPrice="29.90")],
    }
    values.update(overrides)
    extraction = LlmExtraction.model_validate(values)
    return LlmResult(extraction=extraction, model=FAKE_MODEL, input_tokens=120, output_tokens=40)


def test_values_printed_on_a_pdf_are_grounded_despite_format_differences() -> None:
    payload = build_payload(result(), PDF_TEXT, qr_cnpj=None)

    for field in (
        payload.fields.cnpj,
        payload.fields.issuer,
        payload.fields.issue_date,
        payload.fields.total,
    ):
        assert field.confidence == 0.95
        assert field.source is FieldSource.PDF_TEXT
    assert payload.items_confidence == 0.95
    # Values travel normalised, not as printed.
    assert payload.fields.total.value == "1283.80"
    assert payload.fields.issue_date.value == "2026-09-28"
    assert payload.document_kind == "NF-e"
    assert payload.ocr_engine == "pdf_text"
    assert payload.llm_model == FAKE_MODEL
    assert (payload.tokens.input, payload.tokens.output) == (120, 40)
    assert payload.items[0].unit_price == "29.90"


def test_value_the_document_does_not_show_is_scored_as_hallucination() -> None:
    payload = build_payload(result(total="999.99", issuer="Loja Inventada"), PDF_TEXT, qr_cnpj=None)

    assert payload.fields.total.confidence == 0.3
    assert payload.fields.total.source is FieldSource.LLM
    assert payload.fields.issuer.confidence == 0.3
    assert payload.fields.cnpj.confidence == 0.95


def test_missing_value_has_zero_confidence() -> None:
    payload = build_payload(result(issueDate=None, items=[]), PDF_TEXT, qr_cnpj=None)

    assert payload.fields.issue_date.value is None
    assert payload.fields.issue_date.confidence == 0
    assert payload.items == []
    assert payload.items_confidence == 0


def test_qr_code_cnpj_overrides_the_llm_with_full_confidence() -> None:
    payload = build_payload(result(cnpj="60.701.190/0001-04"), PDF_TEXT, qr_cnpj="27.365.041/0001-39")

    assert payload.fields.cnpj.value == "27.365.041/0001-39"
    assert payload.fields.cnpj.confidence == 1.0
    assert payload.fields.cnpj.source is FieldSource.QR_CODE


def test_llm_cnpj_is_formatted_before_it_travels() -> None:
    payload = build_payload(result(cnpj="60701190000104"), PDF_TEXT, qr_cnpj=None)

    assert payload.fields.cnpj.value == "60.701.190/0001-04"
    assert payload.fields.cnpj.confidence == 0.95


def test_item_line_is_as_reliable_as_its_weakest_part() -> None:
    items = [
        LlmItem(description="Resma A4 75g", quantity="5", unitPrice="29.90"),
        LlmItem(description="Resma A4 75g", quantity="5", unitPrice="31.00"),
    ]

    payload = build_payload(result(items=items), PDF_TEXT, qr_cnpj=None)

    assert payload.items_confidence == pytest.approx((0.95 + 0.3) / 2)


def test_ocr_confidence_comes_from_the_recognition_scores() -> None:
    text = DocumentText(
        engine="paddleocr",
        lines=(
            TextLine("Emitente: Papelaria Ponto Final", 0.98),
            TextLine("CNPJ: 60.701.190/0001-04", 0.91),
            TextLine("28/09/2026", 0.88),
            TextLine("Resma A4 75g", 0.97),
            TextLine("29,90", 0.93),
            TextLine("TOTAL 1.283,80", 0.72),
        ),
    )

    payload = build_payload(result(), text, qr_cnpj=None)

    assert payload.fields.cnpj.confidence == 0.91
    assert payload.fields.total.confidence == 0.72
    assert payload.fields.total.source is FieldSource.OCR
    assert payload.items_confidence == 0.93
    assert payload.ocr_engine == "paddleocr"
