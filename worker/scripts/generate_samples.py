"""Renders the six demo documents as PDFs and photos.

Usage (from worker/):  uv run python scripts/generate_samples.py

Writes to backend/src/main/resources/samples/ (demo profile) and frontend/public/samples/
(Upload screen). Output is deterministic: the photo noise uses a fixed seed.
"""

import io
from datetime import date
from decimal import Decimal
from pathlib import Path

import numpy as np
import pymupdf
import segno
from PIL import Image, ImageFilter
from sample_data import SAMPLES, Sample

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIRECTORIES = (
    REPO_ROOT / "backend" / "src" / "main" / "resources" / "samples",
    REPO_ROOT / "frontend" / "public" / "samples",
)

TITLES = {
    "NF-e": "DANFE - Documento Auxiliar da Nota Fiscal Eletrônica",
    "NFC-e": "DANFE NFC-e - Nota Fiscal de Consumidor Eletrônica",
    "NFS-e": "NFS-e - Nota Fiscal de Serviço Eletrônica",
    "Recibo": "RECIBO",
    "Cupom": "CUPOM FISCAL",
}
FONT = "helv"
MARGIN = 48
COLUMNS = (MARGIN, 300, 370, 460)  # description, quantity, unit price, line total
PHOTO_DPI = 150
NOISE_SEED = 20261013
ACCESS_KEY_WEIGHTS = (2, 3, 4, 5, 6, 7, 8, 9)


def money(value: Decimal) -> str:
    """Decimal('1283.80') -> '1.283,80'."""
    integer, cents = f"{value:.2f}".split(".")
    return f"{int(integer):,}".replace(",", ".") + f",{cents}"


def quantity(value: Decimal) -> str:
    return format(value.normalize(), "f").replace(".", ",")


def short_date(iso_date: str) -> str:
    return date.fromisoformat(iso_date).strftime("%d/%m/%Y")


def access_key(sample: Sample) -> str:
    """44 digits: UF, AAMM, issuer CNPJ, model 65, series, number, emission type, code, check digit."""
    issued = date.fromisoformat(sample.issue_date)
    body = f"35{issued:%y%m}{sample.cnpj_digits}65001000001234112345678"
    weighted = sum(int(digit) * ACCESS_KEY_WEIGHTS[i % 8] for i, digit in enumerate(reversed(body)))
    remainder = weighted % 11
    return body + str(0 if remainder < 2 else 11 - remainder)


def render_pdf(sample: Sample) -> bytes:
    document = pymupdf.open()
    page = document.new_page(width=595, height=842)
    y = 70.0

    def line(text: str, size: float = 11, x: float = MARGIN, advance: float = 22) -> None:
        nonlocal y
        page.insert_text((x, y), text, fontsize=size, fontname=FONT)
        y += advance

    line(TITLES[sample.kind], size=14, advance=34)
    line(f"Emitente: {sample.issuer}")
    line(f"CNPJ: {sample.cnpj}")
    line(f"Data de emissão: {short_date(sample.issue_date)}", advance=36)

    for x, header in zip(COLUMNS, ("Descrição", "Qtd", "Vl. unit.", "Vl. total"), strict=True):
        page.insert_text((x, y), header, fontsize=10, fontname=FONT)
    y += 8
    page.draw_line((MARGIN, y), (547, y), width=0.6)
    y += 18
    for item in sample.items:
        cells = (item.description, quantity(item.quantity), money(item.unit_price), money(item.line_total))
        for x, cell in zip(COLUMNS, cells, strict=True):
            page.insert_text((x, y), cell, fontsize=11, fontname=FONT)
        y += 22
    y += 14
    line(f"VALOR TOTAL R$ {money(sample.total)}", size=13, advance=40)

    if sample.kind == "NFC-e":
        key = access_key(sample)
        line("Consulte pela chave de acesso:", size=9, advance=14)
        line(" ".join(key[i : i + 4] for i in range(0, 44, 4)), size=9, advance=20)
        url = f"https://www.nfce.fazenda.sp.gov.br/qrcode?p={key}|2|1|1|A1B2C3D4E5F60718293A4B5C6D7E8F9012345678"
        buffer = io.BytesIO()
        segno.make(url, error="m").save(buffer, kind="png", scale=4, border=2)
        page.insert_image(pymupdf.Rect(MARGIN, y, MARGIN + 150, y + 150), stream=buffer.getvalue())

    content: bytes = document.tobytes(deflate=True, garbage=3, no_new_id=True)
    document.close()
    return content


def render_photo(sample: Sample) -> bytes:
    """A photo of the printed document. Only the low-confidence scenario gets a bad photo."""
    with pymupdf.open(stream=render_pdf(sample), filetype="pdf") as document:
        pixmap = document[0].get_pixmap(dpi=PHOTO_DPI, alpha=False, clip=pymupdf.Rect(20, 30, 575, 420))
    image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
    quality = 90
    if sample.scenario == "Foto com baixa confiança":
        image, quality = _degrade(image), 28
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality)
    return buffer.getvalue()


def _degrade(image: Image.Image) -> Image.Image:
    """Tilted, small, out of focus and grainy: what a hurried phone photo of a receipt looks like."""
    rng = np.random.default_rng(NOISE_SEED)
    tilted = image.rotate(4.0, resample=Image.Resampling.BICUBIC, expand=True, fillcolor=(236, 232, 224))
    small = tilted.resize((tilted.width * 11 // 20, tilted.height * 11 // 20), Image.Resampling.BILINEAR)
    blurred = small.filter(ImageFilter.GaussianBlur(radius=1.15))
    pixels = np.asarray(blurred, dtype=np.float32) * 0.82 + 22
    noisy = pixels + rng.normal(0, 13, pixels.shape)
    return Image.fromarray(np.clip(noisy, 0, 255).astype(np.uint8))


def render(sample: Sample) -> bytes:
    return render_photo(sample) if sample.is_photo else render_pdf(sample)


def main() -> None:
    for directory in OUTPUT_DIRECTORIES:
        directory.mkdir(parents=True, exist_ok=True)
    for sample in SAMPLES:
        content = render(sample)
        for directory in OUTPUT_DIRECTORIES:
            (directory / sample.file_name).write_bytes(content)
        print(f"{sample.file_name}: {len(content)} bytes · {sample.scenario}")


if __name__ == "__main__":
    main()
