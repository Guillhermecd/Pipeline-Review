import re

import pytest

from fiscal_worker.helpers.clock import iso_now
from fiscal_worker.helpers.cnpj import cnpj_from_access_key, format_cnpj
from fiscal_worker.helpers.cpf import MASK, mask_cpf
from fiscal_worker.helpers.formats import date_variants, money_variants, quantity_variants
from fiscal_worker.helpers.text import alphanumeric, fold


class TestMoneyVariants:
    def test_covers_brazilian_and_plain_forms(self) -> None:
        assert money_variants("283.80") == ("283,80", "283.80")

    def test_adds_thousands_separator(self) -> None:
        assert money_variants("1283.80") == ("1.283,80", "1283,80", "1283.80")

    def test_pads_to_two_decimals(self) -> None:
        assert money_variants("42") == ("42,00", "42.00")

    def test_is_empty_for_non_numbers(self) -> None:
        assert money_variants("R$ dez") == ()


class TestDateVariants:
    def test_lists_printed_forms(self) -> None:
        assert date_variants("2026-09-28") == (
            "28/09/2026",
            "28-09-2026",
            "28.09.2026",
            "28/09/26",
            "2026-09-28",
        )

    @pytest.mark.parametrize("value", ["28/09/2026", "2026-13-01", ""])
    def test_is_empty_when_not_an_iso_date(self, value: str) -> None:
        assert date_variants(value) == ()


class TestQuantityVariants:
    def test_integer_has_one_form(self) -> None:
        assert quantity_variants("5") == ("5",)
        assert quantity_variants("5.000") == ("5",)

    def test_fraction_has_comma_and_dot_forms(self) -> None:
        assert quantity_variants("38.2") == ("38,2", "38.2", "38,200", "38.200")


class TestCpfMask:
    def test_masks_formatted_cpf(self) -> None:
        assert mask_cpf("Consumidor: 123.456.789-09 Maria") == f"Consumidor: {MASK} Maria"

    def test_masks_unformatted_cpf_only_when_labeled(self) -> None:
        assert mask_cpf("CPF: 12345678909") == f"CPF: {MASK}"
        assert mask_cpf("CPF do consumidor 12345678909") == f"CPF do consumidor {MASK}"
        assert mask_cpf("Protocolo 12345678909") == "Protocolo 12345678909"

    def test_keeps_cnpj_numeric_and_alphanumeric(self) -> None:
        text = "CNPJ: 60.701.190/0001-04 e 12.ABC.345/01DE-35"
        assert mask_cpf(text) == text

    def test_keeps_access_key(self) -> None:
        key = "35261027365041000139650010000012341123456780"
        assert mask_cpf(f"Chave: {key}") == f"Chave: {key}"
        spaced = " ".join(key[i : i + 4] for i in range(0, 44, 4))
        assert mask_cpf(spaced) == spaced

    def test_masks_every_occurrence(self) -> None:
        assert mask_cpf("111.222.333-44 / 555.666.777-88").count(MASK) == 2


class TestCnpj:
    def test_formats_fourteen_characters(self) -> None:
        assert format_cnpj("60701190000104") == "60.701.190/0001-04"
        assert format_cnpj("60.701.190/0001-04") == "60.701.190/0001-04"
        assert format_cnpj("12abc34501de35") == "12.ABC.345/01DE-35"

    def test_returns_input_when_length_is_wrong(self) -> None:
        assert format_cnpj("6070119000010") == "6070119000010"

    def test_reads_cnpj_from_access_key_inside_qr_url(self) -> None:
        url = "https://www.nfce.fazenda.sp.gov.br/qrcode?p=35261027365041000139650010000012341123456780|2|1|1|AB12"
        assert cnpj_from_access_key(url) == "27.365.041/0001-39"

    def test_reads_alphanumeric_cnpj_from_access_key(self) -> None:
        key = "352610" + "12ABC34501DE35" + "650010000012341123456780"
        assert cnpj_from_access_key(key) == "12.ABC.345/01DE-35"

    @pytest.mark.parametrize(
        "content", ["", "https://example.com/?p=123", "3526102736504100013965001000001234112345678"]
    )
    def test_returns_none_without_a_44_character_key(self, content: str) -> None:
        assert cnpj_from_access_key(content) is None


class TestText:
    def test_fold_drops_accents_case_and_extra_spaces(self) -> None:
        assert fold("  Farmácia   CENTRAL\nEireli ") == "farmacia central eireli"

    def test_alphanumeric_keeps_letters_and_digits_upper_case(self) -> None:
        assert alphanumeric("12.abc.345/01DE-35") == "12ABC34501DE35"


def test_iso_now_has_millisecond_precision_in_utc() -> None:
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", iso_now())
