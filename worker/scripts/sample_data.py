"""The six demo documents, mirroring frontend/src/api/mock/fixtures.ts. Synthetic: no real data."""

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

FIRST_DIGIT_WEIGHTS = (5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
SECOND_DIGIT_WEIGHTS = (6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
CENT = Decimal("0.01")


def _check_digit(digits: list[int], weights: tuple[int, ...]) -> int:
    remainder = sum(digit * weight for digit, weight in zip(digits, weights, strict=True)) % 11
    return 0 if remainder < 2 else 11 - remainder


def cnpj_check_digits(base: str) -> tuple[int, int]:
    digits = [int(char) for char in base]
    first = _check_digit(digits, FIRST_DIGIT_WEIGHTS)
    return first, _check_digit([*digits, first], SECOND_DIGIT_WEIGHTS)


@dataclass(frozen=True)
class SampleItem:
    description: str
    quantity: Decimal
    unit_price: Decimal

    @property
    def line_total(self) -> Decimal:
        return self.quantity * self.unit_price


@dataclass(frozen=True)
class Sample:
    key: str
    file_name: str
    kind: str
    issuer: str
    cnpj_base: str
    issue_date: str
    items: tuple[SampleItem, ...]
    scenario: str
    corrupt_cnpj: bool = False
    total_offset: Decimal = Decimal(0)

    @property
    def cnpj_digits(self) -> str:
        """14 digits. The 'CNPJ com DV inválido' sample carries a wrong second check digit."""
        first, second = cnpj_check_digits(self.cnpj_base)
        if self.corrupt_cnpj:
            second = (second + 1) % 10
        return f"{self.cnpj_base}{first}{second}"

    @property
    def cnpj(self) -> str:
        digits = self.cnpj_digits
        return f"{digits[:2]}.{digits[2:5]}.{digits[5:8]}/{digits[8:12]}-{digits[12:]}"

    @property
    def items_sum(self) -> Decimal:
        total = sum((item.line_total for item in self.items), Decimal(0))
        return total.quantize(CENT, rounding=ROUND_HALF_UP)

    @property
    def total(self) -> Decimal:
        return (self.items_sum + self.total_offset).quantize(CENT, rounding=ROUND_HALF_UP)

    @property
    def is_photo(self) -> bool:
        return self.file_name.endswith(".jpg")


def _item(description: str, quantity: str, unit_price: str) -> SampleItem:
    return SampleItem(description, Decimal(quantity), Decimal(unit_price))


SAMPLES: tuple[Sample, ...] = (
    Sample(
        key="s1",
        file_name="nfe_mercado_sao_jorge.pdf",
        kind="NF-e",
        issuer="Mercado São Jorge Ltda",
        cnpj_base="112223330001",
        issue_date="2026-10-02",
        items=(
            _item("Arroz tipo 1 5kg", "2", "27.90"),
            _item("Feijão carioca 1kg", "3", "8.49"),
            _item("Óleo de soja 900ml", "2", "7.35"),
        ),
        scenario="Deve aprovar",
    ),
    Sample(
        key="s2",
        file_name="recibo_posto_foto.jpg",
        kind="Recibo",
        issuer="Auto Posto Via Norte",
        cnpj_base="045432180001",
        issue_date="2026-10-07",
        items=(_item("Gasolina comum (L)", "38.2", "6.19"),),
        scenario="Foto com baixa confiança",
    ),
    Sample(
        key="s3",
        file_name="nfce_farmacia_central.pdf",
        kind="NFC-e",
        issuer="Farmácia Central EIRELI",
        cnpj_base="273650410001",
        issue_date="2026-10-09",
        items=(_item("Dipirona 500mg", "1", "12.90"), _item("Protetor solar FPS50", "1", "64.90")),
        scenario="CNPJ com DV inválido",
        corrupt_cnpj=True,
    ),
    Sample(
        key="s4",
        file_name="nfe_papelaria_lote.pdf",
        kind="NF-e",
        issuer="Papelaria Ponto Final",
        cnpj_base="607011900001",
        issue_date="2026-09-28",
        items=(
            _item("Resma A4 75g", "5", "29.90"),
            _item("Caneta gel (cx 12)", "2", "38.50"),
            _item("Pasta catálogo", "4", "11.20"),
        ),
        scenario="Soma dos itens ≠ total",
        total_offset=Decimal("12.50"),
    ),
    Sample(
        key="s5",
        file_name="cupom_restaurante.jpg",
        kind="Cupom",
        issuer="Restaurante Dona Lia",
        cnpj_base="334419520001",
        issue_date="2026-10-11",
        items=(_item("Prato executivo", "2", "42.00"), _item("Suco natural", "2", "11.50")),
        scenario="Deve aprovar",
    ),
    Sample(
        key="s6",
        file_name="nfse_consultoria_ti.pdf",
        kind="NFS-e",
        issuer="Kodo Consultoria em TI",
        cnpj_base="091238770001",
        issue_date="2026-10-01",
        items=(_item("Consultoria arquitetura (h)", "16", "220.00"),),
        scenario="Deve aprovar",
    ),
)

SAMPLES_BY_KEY = {sample.key: sample for sample in SAMPLES}
