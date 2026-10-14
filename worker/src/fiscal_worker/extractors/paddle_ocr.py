"""PaddleOCR text recognition for images and scanned PDFs."""

import asyncio
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from fiscal_worker.domain.extraction import TextLine
from fiscal_worker.extractors.rasterizer import Image


class PaddleOcrEngine:
    """Loads the models once and runs every recognition on one dedicated thread.

    Recognition is CPU-bound for seconds; on the event loop it would starve the broker
    heartbeats and get the consumer kicked out. The engine is not thread-safe either.
    """

    def __init__(self, language: str) -> None:
        self._language = language
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="paddle-ocr")
        self._engine: Any = None

    async def start(self) -> None:
        """Model loading (and the first-run download) happens here, not on the first message."""
        await asyncio.get_running_loop().run_in_executor(self._executor, self._load)

    async def recognise(self, images: list[Image]) -> tuple[TextLine, ...]:
        return await asyncio.get_running_loop().run_in_executor(self._executor, self._recognise, images)

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

    def _load(self) -> None:
        if self._engine is not None:
            return
        from paddleocr import PaddleOCR  # heavy import, kept off module load

        self._engine = PaddleOCR(
            lang=self._language,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            # PaddlePaddle 3.3 fails on CPU with oneDNN on ("ConvertPirAttribute2RuntimeAttribute
            # not support"). Plain CPU inference is slower but works everywhere.
            enable_mkldnn=False,
        )

    def _recognise(self, images: list[Image]) -> tuple[TextLine, ...]:
        self._load()
        lines: list[TextLine] = []
        for image in images:
            for page in self._engine.predict(image):
                texts, scores = page["rec_texts"], page["rec_scores"]
                lines.extend(
                    TextLine(text=text.strip(), confidence=float(score))
                    for text, score in zip(texts, scores, strict=True)
                    if text.strip()
                )
        return tuple(lines)
