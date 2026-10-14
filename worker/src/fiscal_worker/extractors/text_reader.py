"""Picks how to read a document: its own text layer when there is one, OCR otherwise."""

import asyncio

from fiscal_worker.domain.errors import PermanentError
from fiscal_worker.domain.extraction import DocumentFile, DocumentText
from fiscal_worker.extractors.image_preprocess import preprocess
from fiscal_worker.extractors.paddle_ocr import PaddleOcrEngine
from fiscal_worker.extractors.pdf_text import extract_text_layer
from fiscal_worker.extractors.rasterizer import IMAGE_MIME_PREFIX, PDF_MIME_TYPE, Image, to_images


class DocumentTextReader:
    def __init__(self, ocr: PaddleOcrEngine) -> None:
        self._ocr = ocr

    async def read(self, file: DocumentFile) -> DocumentText:
        if file.mime_type == PDF_MIME_TYPE:
            text_layer = await asyncio.to_thread(extract_text_layer, file.content)
            if text_layer is not None:
                return text_layer
        elif not file.mime_type.startswith(IMAGE_MIME_PREFIX):
            raise PermanentError(f"formato não suportado: {file.mime_type}")

        images = await asyncio.to_thread(_prepare_images, file)
        lines = await self._ocr.recognise(images)
        if not lines:
            raise PermanentError("imagem ilegível: nenhum texto reconhecido")
        return DocumentText(engine="paddleocr", lines=lines)


def _prepare_images(file: DocumentFile) -> list[Image]:
    return [preprocess(image) for image in to_images(file)]
