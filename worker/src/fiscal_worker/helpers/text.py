"""Text normalisation used to compare what the LLM returned with what is printed on the document."""

import re
import unicodedata

_WHITESPACE = re.compile(r"\s+")
_NON_ALPHANUMERIC = re.compile(r"[^0-9A-Za-z]")


def fold(text: str) -> str:
    """Lower case, no accents, single spaces: 'Farmácia  Central' -> 'farmacia central'."""
    decomposed = unicodedata.normalize("NFKD", text)
    without_accents = "".join(char for char in decomposed if not unicodedata.combining(char))
    return _WHITESPACE.sub(" ", without_accents).strip().lower()


def alphanumeric(text: str) -> str:
    """Only letters and digits, upper case: '12.ABC.345/01DE-35' -> '12ABC34501DE35'."""
    return _NON_ALPHANUMERIC.sub("", text).upper()
