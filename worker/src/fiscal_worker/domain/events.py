"""Message models, validated against contracts/events/*.schema.json by the contract tests."""

from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class BrokerKind(StrEnum):
    KAFKA = "kafka"
    RABBITMQ = "rabbitmq"


class EventType(StrEnum):
    DOCUMENT_RECEIVED = "documento.recebido"
    DOCUMENT_RETRY = "documento.retry"
    DOCUMENT_PROGRESS = "documento.progresso"
    DOCUMENT_EXTRACTED = "documento.extraido"


class Stage(StrEnum):
    OCR = "OCR"
    EXTRACTING = "EXTRACTING"
    RETRY_WAIT = "RETRY_WAIT"


class ContractModel(BaseModel):
    """camelCase on the wire, snake_case in code; unknown fields are a contract violation."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid", frozen=True)


class EventEnvelope(ContractModel):
    event_id: UUID
    event_type: EventType
    schema_version: Literal[1] = 1
    document_id: UUID
    document_code: str = Field(pattern=r"^DOC-[0-9]+$")
    idempotency_key: str = Field(pattern=r"^sha256:[0-9a-f]{64}$")
    request_id: UUID
    attempt: int = Field(ge=1)
    broker: BrokerKind
    occurred_at: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
    payload: dict[str, Any]


class DocumentReceivedPayload(ContractModel):
    file_key: str = Field(min_length=1)
    mime_type: str = Field(pattern=r"^(application/pdf|image/.+)$")
    file_name: str = Field(min_length=1)


class ProgressPayload(ContractModel):
    stage: Stage
    attempt: int = Field(ge=1)
    error: str | None = None
    retry_in_ms: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def _retry_wait_explains_itself(self) -> "ProgressPayload":
        if self.stage is Stage.RETRY_WAIT and (self.error is None or self.retry_in_ms is None):
            raise ValueError("RETRY_WAIT requires error and retryInMs")
        return self
