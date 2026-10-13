"""Google Gemini with structured output: the document's text in, fiscal fields as JSON out."""

from typing import Any

import httpx
from google import genai
from google.genai import errors, types
from pydantic import ValidationError

from fiscal_worker.domain.errors import PermanentError, TransientError
from fiscal_worker.domain.extraction import LlmExtraction, LlmResult

# One answer off the schema may be a fluke; a second one is the document.
SCHEMA_ATTEMPTS = 2
RATE_LIMITED = 429
SERVER_ERROR_FLOOR = 500

_NULLABLE_STRING = {"type": ["string", "null"]}
RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "documentKind": {"type": "string", "enum": ["NF-e", "NFC-e", "NFS-e", "Recibo", "Cupom"]},
        "cnpj": {**_NULLABLE_STRING, "description": "CNPJ do emitente como impresso, ex.: 60.701.190/0001-04"},
        "issuer": {**_NULLABLE_STRING, "description": "Nome ou razão social do emitente, como impresso"},
        "issueDate": {**_NULLABLE_STRING, "description": "Data de emissão no formato AAAA-MM-DD"},
        "total": {**_NULLABLE_STRING, "description": "Valor total com ponto decimal e 2 casas, ex.: 283.80"},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "description": {"type": "string"},
                    "quantity": {"type": "string", "description": "Ponto decimal, até 3 casas, ex.: 38.2"},
                    "unitPrice": {"type": "string", "description": "Ponto decimal e 2 casas, ex.: 29.90"},
                },
                "required": ["description", "quantity", "unitPrice"],
            },
        },
    },
    "required": ["documentKind", "cnpj", "issuer", "issueDate", "total", "items"],
}

PROMPT = """Você extrai dados de documentos fiscais brasileiros a partir do texto lido do documento.

Regras:
- Use somente o que está no texto. Não invente, não corrija e não complete valores.
- Copie CNPJ e nome do emitente exatamente como aparecem, mesmo que pareçam errados.
- Campo ausente ou ilegível: null.
- Valores monetários e quantidades: ponto como separador decimal, sem símbolo de moeda.
- Em "items", um objeto por linha de produto ou serviço. Não inclua totais nem impostos.

Texto do documento:
---
{document_text}
---"""


class GeminiExtractor:
    def __init__(self, api_key: str, model: str, timeout_seconds: int) -> None:
        self._model = model
        self._timeout_seconds = timeout_seconds
        self._client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(
                timeout=timeout_seconds * 1000,
                # A single attempt: the broker's retry policy owns retries (BACKEND.md §5.5).
                retry_options=types.HttpRetryOptions(attempts=1),
            ),
        )

    async def extract(self, document_text: str) -> LlmResult:
        prompt = PROMPT.format(document_text=document_text)
        for _ in range(SCHEMA_ATTEMPTS):
            response = await self._generate(prompt)
            try:
                extraction = LlmExtraction.model_validate_json(response.text or "")
            except ValidationError:
                continue
            usage = response.usage_metadata
            return LlmResult(
                extraction=extraction,
                model=self._model,
                input_tokens=(usage.prompt_token_count or 0) if usage else 0,
                output_tokens=(usage.candidates_token_count or 0) if usage else 0,
            )
        raise PermanentError("resposta do LLM fora do schema")

    async def _generate(self, prompt: str) -> types.GenerateContentResponse:
        config = types.GenerateContentConfig(
            temperature=0,
            response_mime_type="application/json",
            response_json_schema=RESPONSE_SCHEMA,
        )
        try:
            return await self._client.aio.models.generate_content(model=self._model, contents=prompt, config=config)
        except errors.APIError as error:
            raise _classify(error) from error
        except (httpx.TimeoutException, TimeoutError) as error:
            raise TransientError(f"timeout {self._timeout_seconds}s no provedor LLM") from error
        except httpx.TransportError as error:
            raise TransientError("provedor LLM inacessível") from error


def _classify(error: errors.APIError) -> TransientError | PermanentError:
    """429 and 5xx pass; the rest (bad key, bad request, blocked content) will fail again."""
    if error.code == RATE_LIMITED:
        return TransientError("429 rate limit excedido")
    if error.code is not None and error.code >= SERVER_ERROR_FLOOR:
        return TransientError(f"LLM {error.code} {error.status or 'erro do provedor'}".strip())
    return PermanentError(f"LLM {error.code} {error.status or 'requisição recusada'}".strip())
