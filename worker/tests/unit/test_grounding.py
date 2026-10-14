import pytest

from fiscal_worker.domain.extraction import DocumentText, FieldSource, TextLine
from fiscal_worker.helpers.formats import date_variants, money_variants
from fiscal_worker.services.grounding import (
    PDF_TEXT_CONFIDENCE,
    UNGROUNDED,
    TextGrounding,
    matches_cnpj,
    matches_number,
    matches_words,
)


def pdf(*lines: str) -> DocumentText:
    return DocumentText(engine="pdf_text", lines=tuple(TextLine(line, None) for line in lines))


def ocr(*lines: tuple[str, float]) -> DocumentText:
    return DocumentText(engine="paddleocr", lines=tuple(TextLine(text, score) for text, score in lines))


class TestMatchers:
    def test_cnpj_ignores_punctuation_and_ocr_spacing(self) -> None:
        matcher = matches_cnpj("60.701.190/0001-04")
        assert matcher("CNPJ: 60.701.190/0001-04")
        assert matcher("CNPJ:60701190 0001 04")
        assert not matcher("CNPJ: 60.701.190/0001-05")

    def test_cnpj_matches_alphanumeric(self) -> None:
        assert matches_cnpj("12.ABC.345/01DE-35")("cnpj 12.abc.345/01de-35")

    def test_words_ignore_accents_and_case(self) -> None:
        matcher = matches_words("Farmácia Central EIRELI")
        assert matcher("Emitente: FARMACIA  CENTRAL eireli")
        assert not matcher("Emitente: Farmácia do Centro")

    def test_number_matches_the_brazilian_format_of_a_normalised_value(self) -> None:
        matcher = matches_number(money_variants("283.80"))
        assert matcher("VALOR TOTAL R$ 283,80")
        assert matcher("TOTAL 283, 80")

    def test_number_is_not_found_inside_a_longer_number(self) -> None:
        matcher = matches_number(money_variants("5.00"))
        assert not matcher("15,00")
        assert not matcher("5,000")
        assert not matcher("1.235,00 e 2,5,00")
        assert matcher("un 5,00")

    def test_number_in_a_neighbouring_column_is_still_found(self) -> None:
        line = "Gasolina comum (L)  38,2  6,19"
        assert matches_number(money_variants("6.19"))(line)
        assert not matches_number(money_variants("26.19"))(line)

    def test_date_matches_printed_form(self) -> None:
        matcher = matches_number(date_variants("2026-09-28"))
        assert matcher("Data de emissão: 28/09/2026")
        assert not matcher("Data de emissão: 28/09/2025")

    @pytest.mark.parametrize("matcher", [matches_cnpj(""), matches_cnpj("./-"), matches_words("  ")])
    def test_empty_value_never_matches(self, matcher) -> None:  # type: ignore[no-untyped-def]
        assert not matcher("qualquer texto")


class TestTextGrounding:
    def test_pdf_text_value_gets_fixed_confidence(self) -> None:
        score = TextGrounding(pdf("Emitente: Papelaria Ponto Final")).score(
            matches_words("Papelaria Ponto Final")
        )

        assert score.confidence == PDF_TEXT_CONFIDENCE
        assert score.source is FieldSource.PDF_TEXT

    def test_value_absent_from_the_text_is_treated_as_hallucination(self) -> None:
        assert TextGrounding(pdf("Emitente: Outra Loja")).score(matches_words("Papelaria")) == UNGROUNDED
        assert TextGrounding(ocr(("Outra Loja", 0.99))).score(matches_words("Papelaria")) == UNGROUNDED

    def test_ocr_value_takes_the_score_of_its_line(self) -> None:
        text = ocr(("RECIBO", 0.99), ("CNPJ: 04.543.218/0001-43", 0.89), ("TOTAL 236,46", 0.60))

        score = TextGrounding(text).score(matches_cnpj("04.543.218/0001-43"))

        assert score.confidence == pytest.approx(0.89)
        assert score.source is FieldSource.OCR

    def test_ocr_value_on_several_lines_averages_them(self) -> None:
        text = ocr(("Suco natural", 0.90), ("Suco natural", 0.80), ("outro", 0.10))

        assert TextGrounding(text).score(matches_words("Suco natural")).confidence == pytest.approx(0.85)

    def test_ocr_value_split_across_lines_takes_the_page_average(self) -> None:
        text = ocr(("Restaurante", 0.90), ("Dona Lia", 0.70))

        score = TextGrounding(text).score(matches_words("Restaurante Dona Lia"))

        assert score.confidence == pytest.approx(0.80)
        assert score.source is FieldSource.OCR
