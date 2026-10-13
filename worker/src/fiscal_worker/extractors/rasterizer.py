"""Turns an uploaded file into page images (BGR arrays), for OCR and QR Code reading."""

from typing import Any

import cv2
import numpy as np
import pymupdf

from fiscal_worker.domain.errors import PermanentError
from fiscal_worker.domain.extraction import DocumentFile

PDF_MIME_TYPE = "application/pdf"
IMAGE_MIME_PREFIX = "image/"
RENDER_DPI = 200
MAX_PAGES = 5

Image = np.ndarray[Any, np.dtype[np.uint8]]


def open_pdf(content: bytes) -> pymupdf.Document:
    try:
        document = pymupdf.open(stream=content, filetype="pdf")
    except (pymupdf.FileDataError, pymupdf.EmptyFileError) as error:
        raise PermanentError("arquivo corrompido: PDF ilegível") from error
    if document.needs_pass:
        document.close()
        raise PermanentError("PDF protegido por senha")
    return document


def to_images(file: DocumentFile) -> list[Image]:
    if file.mime_type == PDF_MIME_TYPE:
        return _render_pdf(file.content)
    if file.mime_type.startswith(IMAGE_MIME_PREFIX):
        return [_decode_image(file.content)]
    raise PermanentError(f"formato não suportado: {file.mime_type}")


def _render_pdf(content: bytes) -> list[Image]:
    with open_pdf(content) as document:
        return [_page_to_image(page) for page in list(document)[:MAX_PAGES]]


def _page_to_image(page: pymupdf.Page) -> Image:
    pixmap = page.get_pixmap(dpi=RENDER_DPI, alpha=False)
    rgb = np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(pixmap.height, pixmap.width, pixmap.n)
    return np.asarray(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), dtype=np.uint8)


def _decode_image(content: bytes) -> Image:
    image = cv2.imdecode(np.frombuffer(content, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise PermanentError("arquivo corrompido: imagem ilegível")
    return np.asarray(image, dtype=np.uint8)
