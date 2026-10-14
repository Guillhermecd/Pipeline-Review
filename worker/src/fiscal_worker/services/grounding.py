"""Grounding: does a value the LLM returned actually appear in the document's text?

The LLM gives no calibrated probability, so confidence comes from objective signals (BACKEND.md §8.2).
"""

import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from statistics import fmean

from fiscal_worker.domain.extraction import DocumentText, FieldSource
from fiscal_worker.helpers.text import alphanumeric, fold

QR_CODE_CONFIDENCE = 1.0
# A PDF text layer has no recognition score. A value found in it is as reliable as the layer itself.
PDF_TEXT_CONFIDENCE = 0.95
# Not printed anywhere on the document: most likely a hallucination.
UNGROUNDED_CONFIDENCE = 0.3
MISSING_CONFIDENCE = 0.0

Matcher = Callable[[str], bool]


@dataclass(frozen=True)
class Score:
    confidence: float
    source: FieldSource


UNGROUNDED = Score(UNGROUNDED_CONFIDENCE, FieldSource.LLM)
MISSING = Score(MISSING_CONFIDENCE, FieldSource.LLM)


def matches_cnpj(value: str) -> Matcher:
    needle = alphanumeric(value)
    return lambda text: bool(needle) and needle in alphanumeric(text)


def matches_words(value: str) -> Matcher:
    """Accent, case and spacing insensitive: OCR often drops a cedilla or doubles a space."""
    needle = fold(value)
    return lambda text: bool(needle) and needle in fold(text)


def matches_number(variants: Sequence[str]) -> Matcher:
    """Any printed form of a number, never as part of a longer one ('5,00' is not in '15,00').

    OCR may put spaces around a separator ('283, 80'). Spaces are tolerated there only: removing
    them from the whole line would glue neighbouring columns together ('38,2 6,19').
    """
    patterns = [re.compile(rf"(?<![\d.,]){_spaced(variant)}(?!\d)") for variant in variants]
    return lambda text: any(pattern.search(text) for pattern in patterns)


def _spaced(number: str) -> str:
    return "".join(char if char.isdigit() else rf"\s*{re.escape(char)}\s*" for char in number)


class TextGrounding:
    """Scores values against one document's text."""

    def __init__(self, document: DocumentText) -> None:
        self._document = document

    def score(self, appears_in: Matcher) -> Score:
        if self._document.engine == "pdf_text":
            return self._score_pdf_text(appears_in)
        return self._score_ocr(appears_in)

    def _score_pdf_text(self, appears_in: Matcher) -> Score:
        if appears_in(self._document.text):
            return Score(PDF_TEXT_CONFIDENCE, FieldSource.PDF_TEXT)
        return UNGROUNDED

    def _score_ocr(self, appears_in: Matcher) -> Score:
        """Mean recognition score of the lines holding the value, or of the page when it spans lines."""
        lines = self._document.lines
        matching = [
            line.confidence for line in lines if line.confidence is not None and appears_in(line.text)
        ]
        if matching:
            return Score(fmean(matching), FieldSource.OCR)
        every = [line.confidence for line in lines if line.confidence is not None]
        if every and appears_in(self._document.text):
            return Score(fmean(every), FieldSource.OCR)
        return UNGROUNDED
