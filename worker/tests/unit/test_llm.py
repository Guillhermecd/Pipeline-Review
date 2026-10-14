"""GeminiExtractor against a stubbed SDK client: answer parsing and error classification."""

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from google.genai import errors

from fiscal_worker.domain.errors import PermanentError, TransientError
from fiscal_worker.extractors.llm import RESPONSE_SCHEMA, GeminiExtractor

VALID_ANSWER = {
    "documentKind": "NF-e",
    "cnpj": "60.701.190/0001-04",
    "issuer": "Papelaria Ponto Final",
    "issueDate": "2026-09-28",
    "total": "283.80",
    "items": [{"description": "Resma A4 75g", "quantity": "5", "unitPrice": "29.90"}],
}


class StubModels:
    """Stands in for `client.aio.models`: returns queued answers or raises queued errors."""

    def __init__(self, *outcomes: Any) -> None:
        self._outcomes = list(outcomes)
        self.calls: list[dict[str, Any]] = []

    async def generate_content(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        usage = SimpleNamespace(prompt_token_count=1830, candidates_token_count=412)
        return SimpleNamespace(text=outcome, usage_metadata=usage)


def extractor(*outcomes: Any) -> tuple[GeminiExtractor, StubModels]:
    gemini = GeminiExtractor(api_key="test-key", model="gemini-test", timeout_seconds=30)
    models = StubModels(*outcomes)
    gemini._client = SimpleNamespace(aio=SimpleNamespace(models=models))  # type: ignore[assignment]
    return gemini, models


def api_error(code: int, status: str) -> errors.APIError:
    return errors.APIError(code, {"error": {"code": code, "status": status, "message": status}})


async def test_parses_a_valid_answer_and_reports_token_usage() -> None:
    gemini, models = extractor(json.dumps(VALID_ANSWER))

    result = await gemini.extract("texto do documento")

    assert result.extraction.total == "283.80"
    assert result.extraction.items[0].unitPrice == "29.90"
    assert (result.model, result.input_tokens, result.output_tokens) == ("gemini-test", 1830, 412)
    call = models.calls[0]
    assert call["model"] == "gemini-test"
    assert "texto do documento" in call["contents"]
    assert call["config"].response_mime_type == "application/json"
    assert call["config"].response_json_schema == RESPONSE_SCHEMA
    assert call["config"].temperature == 0


async def test_accepts_null_fields() -> None:
    gemini, _ = extractor(json.dumps({**VALID_ANSWER, "cnpj": None, "total": None, "items": []}))

    result = await gemini.extract("texto")

    assert result.extraction.cnpj is None
    assert result.extraction.total is None


async def test_asks_once_more_when_the_answer_is_off_schema() -> None:
    gemini, models = extractor(json.dumps({**VALID_ANSWER, "total": "283,80"}), json.dumps(VALID_ANSWER))

    result = await gemini.extract("texto")

    assert result.extraction.total == "283.80"
    assert len(models.calls) == 2


@pytest.mark.parametrize("bad", ["", "não é json", json.dumps({**VALID_ANSWER, "documentKind": "Boleto"})])
async def test_gives_up_as_permanent_after_two_off_schema_answers(bad: str) -> None:
    gemini, models = extractor(bad, bad)

    with pytest.raises(PermanentError, match="resposta do LLM fora do schema"):
        await gemini.extract("texto")
    assert len(models.calls) == 2


@pytest.mark.parametrize(
    ("error", "message"),
    [
        (api_error(429, "RESOURCE_EXHAUSTED"), "429 rate limit excedido"),
        (api_error(503, "UNAVAILABLE"), "LLM 503 UNAVAILABLE"),
        (api_error(500, "INTERNAL"), "LLM 500 INTERNAL"),
        (httpx.ReadTimeout("timed out"), "timeout 30s no provedor LLM"),
        (TimeoutError(), "timeout 30s no provedor LLM"),
        (httpx.ConnectError("refused"), "provedor LLM inacessível"),
    ],
)
async def test_provider_trouble_is_transient_and_not_retried_here(error: Exception, message: str) -> None:
    """One call only: the broker's retry policy owns retries."""
    gemini, models = extractor(error)

    with pytest.raises(TransientError) as raised:
        await gemini.extract("texto")

    assert str(raised.value) == message
    assert len(models.calls) == 1


@pytest.mark.parametrize("code", [400, 401, 403, 404])
async def test_rejected_request_is_permanent(code: int) -> None:
    gemini, models = extractor(api_error(code, "INVALID_ARGUMENT"))

    with pytest.raises(PermanentError, match=f"LLM {code} INVALID_ARGUMENT"):
        await gemini.extract("texto")
    assert len(models.calls) == 1


def test_sdk_retries_are_disabled() -> None:
    gemini = GeminiExtractor(api_key="test-key", model="gemini-test", timeout_seconds=30)

    options = gemini._client._api_client._http_options
    assert options.retry_options is not None
    assert options.retry_options.attempts == 1
    assert options.timeout == 30_000
