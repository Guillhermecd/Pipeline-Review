"""Wires the worker together and consumes from both brokers at once."""

import asyncio
import contextlib
import logging

from fiscal_worker.config.settings import Settings
from fiscal_worker.consumers.document_consumer import DocumentConsumer
from fiscal_worker.domain.events import BrokerKind
from fiscal_worker.extractors.llm import GeminiExtractor
from fiscal_worker.extractors.paddle_ocr import PaddleOcrEngine
from fiscal_worker.extractors.qr_code import PyzbarQrCodeReader
from fiscal_worker.extractors.text_reader import DocumentTextReader
from fiscal_worker.messaging.kafka import KafkaBroker
from fiscal_worker.messaging.protocols import MessageBroker, MessageHandler
from fiscal_worker.messaging.rabbitmq import RabbitMqBroker
from fiscal_worker.publishers.event_publisher import EventPublisher
from fiscal_worker.services.extraction_service import ExtractionService
from fiscal_worker.storage.s3_file_store import S3FileStore

log = logging.getLogger(__name__)

RESTART_DELAY_SECONDS = 5.0


async def serve(settings: Settings) -> None:
    ocr = PaddleOcrEngine(settings.ocr_lang)
    brokers: dict[BrokerKind, MessageBroker] = {
        BrokerKind.KAFKA: KafkaBroker(settings.kafka_bootstrap_servers),
        BrokerKind.RABBITMQ: RabbitMqBroker(settings.rabbitmq_url),
    }
    service = ExtractionService(
        files=S3FileStore(
            endpoint=settings.s3_endpoint,
            region=settings.s3_region,
            access_key_id=settings.s3_access_key_id,
            secret_access_key=settings.s3_secret_access_key.get_secret_value(),
            bucket=settings.s3_bucket,
        ),
        text_reader=DocumentTextReader(ocr),
        qr_reader=PyzbarQrCodeReader(),
        llm=GeminiExtractor(
            api_key=settings.gemini_api_key.get_secret_value(),
            model=settings.llm_model,
            timeout_seconds=settings.llm_timeout_seconds,
        ),
    )
    consumer = DocumentConsumer(service, EventPublisher(brokers))

    log.info("Loading OCR models")
    await ocr.start()
    try:
        async with asyncio.TaskGroup() as tasks:
            for broker in brokers.values():
                tasks.create_task(keep_consuming(broker, consumer.handle), name=f"consume-{broker.kind}")
    finally:
        ocr.close()


async def keep_consuming(broker: MessageBroker, handler: MessageHandler) -> None:
    """One broker being down, at start or later, must not stop documents of the other one."""
    while True:
        try:
            await broker.start()
            log.info("Consuming from %s", broker.kind)
            await broker.consume(handler)
        except Exception:
            log.exception("%s consumer stopped; trying again in %.0fs", broker.kind, RESTART_DELAY_SECONDS)
        finally:
            with contextlib.suppress(Exception):
                await broker.stop()
        await asyncio.sleep(RESTART_DELAY_SECONDS)


def run() -> None:
    settings = Settings()
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    try:
        asyncio.run(serve(settings))
    except KeyboardInterrupt:
        log.info("Worker stopped")


if __name__ == "__main__":
    run()
