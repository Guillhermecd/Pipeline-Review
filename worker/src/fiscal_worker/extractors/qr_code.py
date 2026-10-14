"""NFC-e QR Code (pyzbar). Its access key carries the issuer CNPJ as data, not as printed text."""

import asyncio

from pyzbar.pyzbar import ZBarSymbol, decode

from fiscal_worker.domain.extraction import DocumentFile
from fiscal_worker.extractors.rasterizer import to_images
from fiscal_worker.helpers.cnpj import cnpj_from_access_key


class PyzbarQrCodeReader:
    async def read_cnpj(self, file: DocumentFile) -> str | None:
        return await asyncio.to_thread(_read_cnpj, file)


def _read_cnpj(file: DocumentFile) -> str | None:
    for image in to_images(file):
        for symbol in decode(image, symbols=[ZBarSymbol.QRCODE]):
            cnpj = cnpj_from_access_key(symbol.data.decode("utf-8", errors="replace"))
            if cnpj is not None:
                return cnpj
    return None
