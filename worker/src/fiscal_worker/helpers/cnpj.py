"""CNPJ formatting and its place inside the NFC-e access key."""

import re

from fiscal_worker.helpers.text import alphanumeric

CNPJ_LENGTH = 14
ACCESS_KEY_LENGTH = 44
# Access key layout: UF (2) + AAMM (4) + CNPJ (14) + model, series, number...
_CNPJ_START = 6

# Digits, plus upper-case letters for the alphanumeric CNPJ the Receita issues since July 2026.
_ACCESS_KEY = re.compile(r"(?<![0-9A-Z])[0-9A-Z]{44}(?![0-9A-Z])")


def format_cnpj(raw: str) -> str:
    """'60701190000104' -> '60.701.190/0001-04'. Anything that is not 14 characters is returned as is."""
    clean = alphanumeric(raw)
    if len(clean) != CNPJ_LENGTH:
        return raw
    return f"{clean[:2]}.{clean[2:5]}.{clean[5:8]}/{clean[8:12]}-{clean[12:]}"


def cnpj_from_access_key(content: str) -> str | None:
    """Finds a 44-character access key in a QR Code payload and returns the issuer CNPJ, formatted."""
    match = _ACCESS_KEY.search(content)
    if match is None:
        return None
    key = match.group(0)
    return format_cnpj(key[_CNPJ_START : _CNPJ_START + CNPJ_LENGTH])
