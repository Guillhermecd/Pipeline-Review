"""Real PaddleOCR on the photo samples, with a perfect LLM: only the reading quality varies.

Slow: loads the OCR models (downloaded on first run) and recognises on CPU.
"""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio

from fiscal_worker.domain.extraction import DocumentFile, ExtractionPayload, LlmResult
from fiscal_worker.extractors.paddle_ocr import PaddleOcrEngine
from fiscal_worker.extractors.text_reader import DocumentTextReader
from fiscal_worker.services.payload_builder import build_payload
from generate_samples import render
from sample_data import SAMPLES_BY_KEY, Sample
from support import FAKE_MODEL, truth, weighted_score

pytestmark = [pytest.mark.ocr, pytest.mark.asyncio(loop_scope="module")]

APPROVAL_THRESHOLD = 0.85


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def reader() -> AsyncIterator[DocumentTextReader]:
    engine = PaddleOcrEngine("pt")
    await engine.start()
    yield DocumentTextReader(engine)
    engine.close()


async def extract(reader: DocumentTextReader, sample: Sample) -> ExtractionPayload:
    text = await reader.read(DocumentFile(sample.file_name, "image/jpeg", render(sample)))
    assert text.engine == "paddleocr"
    return build_payload(LlmResult(truth(sample), FAKE_MODEL, 0, 0), text, qr_cnpj=None)


async def test_clean_photo_is_read_well_enough_to_approve(reader: DocumentTextReader) -> None:
    sample = SAMPLES_BY_KEY["s5"]

    payload = await extract(reader, sample)

    assert payload.fields.cnpj.confidence >= 0.9
    assert payload.fields.total.confidence >= 0.9
    assert payload.items_confidence >= 0.9
    assert weighted_score(payload) >= APPROVAL_THRESHOLD


async def test_bad_photo_scores_below_the_review_threshold(reader: DocumentTextReader) -> None:
    """The 'Foto com baixa confiança' scenario must land in human review."""
    payload = await extract(reader, SAMPLES_BY_KEY["s2"])

    assert weighted_score(payload) < APPROVAL_THRESHOLD
