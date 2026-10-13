"""Round trip of the generated demo documents: what is rendered must be what gets extracted."""

from decimal import Decimal

import pymupdf
import pytest

from fiscal_worker.domain.errors import PermanentError
from fiscal_worker.domain.extraction import DocumentFile, FieldSource, LlmResult
from fiscal_worker.extractors.pdf_text import extract_text_layer
from fiscal_worker.extractors.qr_code import PyzbarQrCodeReader
from fiscal_worker.extractors.rasterizer import to_images
from fiscal_worker.services.payload_builder import build_payload
from generate_samples import access_key, render, render_pdf
from sample_data import SAMPLES, SAMPLES_BY_KEY, Sample, cnpj_check_digits
from support import FAKE_MODEL, truth, weighted_score

PDF_SAMPLES = [sample for sample in SAMPLES if not sample.is_photo]
APPROVAL_THRESHOLD = 0.85


def pdf_file(sample: Sample) -> DocumentFile:
    return DocumentFile(sample.file_name, "application/pdf", render_pdf(sample))


class TestSampleData:
    """Values must equal frontend/src/api/mock/fixtures.ts, which the screens already show."""

    def test_valid_cnpj_check_digits(self) -> None:
        assert SAMPLES_BY_KEY["s4"].cnpj == "60.701.190/0001-04"
        assert SAMPLES_BY_KEY["s1"].cnpj == "11.222.333/0001-81"

    def test_corrupted_cnpj_differs_only_in_the_last_digit(self) -> None:
        sample = SAMPLES_BY_KEY["s3"]
        first, second = cnpj_check_digits(sample.cnpj_base)

        assert sample.cnpj_digits == f"{sample.cnpj_base}{first}{(second + 1) % 10}"

    def test_totals(self) -> None:
        assert SAMPLES_BY_KEY["s1"].total == Decimal("95.97")
        assert SAMPLES_BY_KEY["s2"].total == Decimal("236.46")
        assert SAMPLES_BY_KEY["s5"].total == Decimal("107.00")
        assert SAMPLES_BY_KEY["s6"].total == Decimal("3520.00")

    def test_sum_mismatch_sample_keeps_both_values(self) -> None:
        sample = SAMPLES_BY_KEY["s4"]

        assert (sample.items_sum, sample.total) == (Decimal("271.30"), Decimal("283.80"))

    def test_generation_is_deterministic(self) -> None:
        for sample in SAMPLES:
            assert render(sample) == render(sample), sample.file_name


@pytest.mark.parametrize("sample", PDF_SAMPLES, ids=lambda sample: sample.key)
def test_pdf_text_layer_carries_every_field_with_accents_intact(sample: Sample) -> None:
    text = extract_text_layer(render_pdf(sample))

    assert text is not None
    assert text.engine == "pdf_text"
    assert sample.issuer in text.text
    assert sample.cnpj in text.text
    for item in sample.items:
        assert item.description in text.text


@pytest.mark.parametrize("sample", PDF_SAMPLES, ids=lambda sample: sample.key)
def test_pdf_sample_is_fully_grounded_with_a_perfect_llm(sample: Sample) -> None:
    text = extract_text_layer(render_pdf(sample))
    assert text is not None

    payload = build_payload(LlmResult(truth(sample), FAKE_MODEL, 0, 0), text, qr_cnpj=None)

    assert payload.fields.total.value == f"{sample.total:.2f}"
    assert payload.fields.total.source is FieldSource.PDF_TEXT
    assert weighted_score(payload) == pytest.approx(0.95)
    assert weighted_score(payload) >= APPROVAL_THRESHOLD


async def test_nfce_qr_code_carries_the_same_corrupted_cnpj_as_the_printed_one() -> None:
    """A valid CNPJ in the QR Code would override the printed one and hide the scenario."""
    sample = SAMPLES_BY_KEY["s3"]

    cnpj = await PyzbarQrCodeReader().read_cnpj(pdf_file(sample))

    assert cnpj == sample.cnpj
    assert len(access_key(sample)) == 44
    assert access_key(sample)[6:20] == sample.cnpj_digits


@pytest.mark.parametrize("key", ["s1", "s4", "s6"])
async def test_documents_without_qr_code_yield_no_cnpj(key: str) -> None:
    assert await PyzbarQrCodeReader().read_cnpj(pdf_file(SAMPLES_BY_KEY[key])) is None


class TestUnreadableFiles:
    def test_corrupt_pdf_is_permanent(self) -> None:
        with pytest.raises(PermanentError, match="arquivo corrompido"):
            extract_text_layer(b"%PDF-1.4 not really a pdf")

    def test_password_protected_pdf_is_permanent(self) -> None:
        with pymupdf.open(stream=render_pdf(SAMPLES_BY_KEY["s1"]), filetype="pdf") as document:
            protected = document.tobytes(
                encryption=pymupdf.PDF_ENCRYPT_AES_256, owner_pw="owner", user_pw="segredo"
            )

        with pytest.raises(PermanentError, match="PDF protegido por senha"):
            extract_text_layer(protected)

    def test_undecodable_image_is_permanent(self) -> None:
        with pytest.raises(PermanentError, match="imagem ilegível"):
            to_images(DocumentFile("foto.jpg", "image/jpeg", b"not an image"))

    def test_unsupported_type_is_permanent(self) -> None:
        with pytest.raises(PermanentError, match="formato não suportado: text/csv"):
            to_images(DocumentFile("dados.csv", "text/csv", b"a;b"))

    def test_pdf_without_text_layer_asks_for_ocr(self) -> None:
        document = pymupdf.open()
        document.new_page()
        blank = document.tobytes()
        document.close()

        assert extract_text_layer(blank) is None
