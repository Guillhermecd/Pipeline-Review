"""Environment configuration (worker/.env)."""

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    kafka_bootstrap_servers: str = "localhost:9092"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5673"

    s3_endpoint: str = "http://localhost:9050"
    s3_region: str = "us-east-1"
    s3_bucket: str = "pipeline-fiscal"
    s3_access_key_id: str
    s3_secret_access_key: SecretStr

    gemini_api_key: SecretStr
    llm_model: str = Field(min_length=1)
    llm_timeout_seconds: int = Field(default=30, gt=0)

    ocr_lang: str = "pt"

    otel_service_name: str = "fiscal-worker"
    # OTLP over HTTP (Jaeger: http://localhost:4318). Empty: spans are created but not exported.
    otel_exporter_otlp_endpoint: str | None = None
    log_level: str = "INFO"
