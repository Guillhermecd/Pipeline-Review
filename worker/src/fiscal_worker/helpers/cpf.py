"""CPF masking. Text leaves for the LLM provider only after individuals' CPFs are removed (LGPD)."""

import re

MASK = "***.***.***-**"

# 000.000.000-00, not glued to other digits (a CNPJ or an access key never matches this shape).
_FORMATTED = re.compile(r"(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")
# Unformatted digits are only a CPF when the document says so: 11 digits alone could be anything.
_LABELED = re.compile(r"(?i)(\bCPF\b[^\d\n]{0,12})(\d{11})(?!\d)")


def mask_cpf(text: str) -> str:
    masked = _FORMATTED.sub(MASK, text)
    return _LABELED.sub(lambda match: match.group(1) + MASK, masked)
