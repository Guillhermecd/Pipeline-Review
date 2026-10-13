"""The JSON Schemas in contracts/ and the Pydantic models must accept and reject the same messages.

The Java side validates the same examples against the same schemas: change the contract and
both sides break together.
"""

from typing import Any

import pytest
from jsonschema import Draft202012Validator, ValidationError
from pydantic import BaseModel
from pydantic import ValidationError as PydanticValidationError

from fiscal_worker.domain.events import DocumentReceivedPayload, EventEnvelope, ProgressPayload
from fiscal_worker.domain.extraction import ExtractionPayload
from support import load_example, load_schema

ENVELOPE_SCHEMA = "envelope.schema.json"
CASES: list[tuple[str, str, type[BaseModel]]] = [
    ("documento.recebido.json", "documento-recebido.payload.schema.json", DocumentReceivedPayload),
    ("documento.retry.json", "documento-recebido.payload.schema.json", DocumentReceivedPayload),
    ("documento.progresso.ocr.json", "documento-progresso.payload.schema.json", ProgressPayload),
    ("documento.progresso.retry-wait.json", "documento-progresso.payload.schema.json", ProgressPayload),
    ("documento.extraido.json", "documento-extraido.payload.schema.json", ExtractionPayload),
]


def validator(schema_name: str) -> Draft202012Validator:
    schema = load_schema(schema_name)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


@pytest.mark.parametrize(("example_name", "payload_schema", "payload_model"), CASES)
def test_example_is_valid_for_schema_and_model(
    example_name: str, payload_schema: str, payload_model: type[BaseModel]
) -> None:
    example = load_example(example_name)

    validator(ENVELOPE_SCHEMA).validate(example)
    validator(payload_schema).validate(example["payload"])
    envelope = EventEnvelope.model_validate(example)
    payload_model.model_validate(envelope.payload)


@pytest.mark.parametrize(("example_name", "payload_schema", "payload_model"), CASES)
def test_model_serialises_back_to_the_same_message(
    example_name: str, payload_schema: str, payload_model: type[BaseModel]
) -> None:
    example = load_example(example_name)

    envelope = EventEnvelope.model_validate(example)
    payload = payload_model.model_validate(envelope.payload)

    assert envelope.model_dump(mode="json", by_alias=True) == example
    assert payload.model_dump(mode="json", by_alias=True, exclude_none=True) == example["payload"]


def broken(example_name: str, path: tuple[str, ...], value: Any) -> dict[str, Any]:
    message = load_example(example_name)
    target = message
    for key in path[:-1]:
        target = target[key]
    if value is ...:
        del target[path[-1]]
    else:
        target[path[-1]] = value
    return message


ENVELOPE_VIOLATIONS = [
    (("requestId",), ...),
    (("attempt",), 0),
    (("broker",), "sqs"),
    (("eventType",), "documento.apagado"),
    (("idempotencyKey",), "md5:abc"),
    (("occurredAt",), "2026-10-13 14:37:23"),
    (("schemaVersion",), 2),
    (("extra",), "campo desconhecido"),
]


@pytest.mark.parametrize(("path", "value"), ENVELOPE_VIOLATIONS)
def test_envelope_violation_is_rejected_by_schema_and_model(path: tuple[str, ...], value: Any) -> None:
    message = broken("documento.recebido.json", path, value)

    with pytest.raises(ValidationError):
        validator(ENVELOPE_SCHEMA).validate(message)
    with pytest.raises(PydanticValidationError):
        EventEnvelope.model_validate(message)


EXTRACTION_VIOLATIONS = [
    (("fields", "total", "value"), "283,80"),
    (("fields", "total", "value"), 283.8),
    (("fields", "issueDate", "value"), "28/09/2026"),
    (("fields", "cnpj", "confidence"), 1.2),
    (("fields", "cnpj", "source"), "palpite"),
    (("fields", "issuer"), ...),
    (("documentKind",), "Boleto"),
    (("itemsConfidence",), -0.1),
    (("ocrEngine",), "tesseract"),
    (("tokens", "input"), -1),
]


@pytest.mark.parametrize(("path", "value"), EXTRACTION_VIOLATIONS)
def test_extraction_violation_is_rejected_by_schema_and_model(path: tuple[str, ...], value: Any) -> None:
    payload = broken("documento.extraido.json", ("payload", *path), value)["payload"]

    with pytest.raises(ValidationError):
        validator("documento-extraido.payload.schema.json").validate(payload)
    with pytest.raises(PydanticValidationError):
        ExtractionPayload.model_validate(payload)


def test_item_money_must_be_a_decimal_string() -> None:
    payload = load_example("documento.extraido.json")["payload"]
    payload["items"][0]["unitPrice"] = "29,9"

    with pytest.raises(ValidationError):
        validator("documento-extraido.payload.schema.json").validate(payload)
    with pytest.raises(PydanticValidationError):
        ExtractionPayload.model_validate(payload)


def test_null_field_value_is_allowed() -> None:
    payload = load_example("documento.extraido.json")["payload"]
    payload["fields"]["total"] = {"value": None, "confidence": 0, "source": "llm"}

    validator("documento-extraido.payload.schema.json").validate(payload)
    assert ExtractionPayload.model_validate(payload).fields.total.value is None


def test_retry_wait_progress_requires_error_and_delay() -> None:
    payload = {"stage": "RETRY_WAIT", "attempt": 1}

    with pytest.raises(ValidationError):
        validator("documento-progresso.payload.schema.json").validate(payload)
    with pytest.raises(PydanticValidationError):
        ProgressPayload.model_validate(payload)
