"""Queue and topic names for both brokers (BACKEND.md §5.2). They are part of the frontend contract.

The backend declares the same objects. RabbitMQ answers 406 PRECONDITION_FAILED to whoever
declares a queue second with different arguments, so both sides are checked against
contracts/topology.json by their tests.
"""

from enum import StrEnum
from typing import Any


class QueueRole(StrEnum):
    INPUT = "input"
    OUTPUT = "output"
    PROGRESS = "progress"
    DEAD_LETTER = "dead_letter"


# Wait before each retry. A failure on the attempt after the last one goes to the dead-letter queue.
RETRY_BACKOFF_MS: tuple[int, ...] = (1000, 2000, 4000)

KAFKA_TOPICS: dict[QueueRole, str] = {
    QueueRole.INPUT: "documentos.recebidos",
    QueueRole.OUTPUT: "documentos.extraidos",
    QueueRole.PROGRESS: "documentos.progresso",
    QueueRole.DEAD_LETTER: "documentos.recebidos.DLT",
}
KAFKA_RETRY_TOPIC = "documentos.recebidos-retry"
KAFKA_CONSUMER_GROUP = "extrator-py"
KAFKA_RETRY_CONSUMER_GROUP = "extrator-py-retry"
KAFKA_PARTITIONS = 3
KAFKA_REPLICATION_FACTOR = 1
# Epoch milliseconds before which a message in the retry topic must not go back to the input topic.
KAFKA_NOT_BEFORE_HEADER = "x-not-before"

RABBIT_EXCHANGE = "documentos"
RABBIT_PREFETCH = 10
RABBIT_QUEUES: dict[QueueRole, str] = {
    QueueRole.INPUT: "extracao.q",
    QueueRole.OUTPUT: "validacao.q",
    QueueRole.PROGRESS: "progresso.q",
    QueueRole.DEAD_LETTER: "extracao.dlq",
}
# One queue per delay: a single queue with per-message TTL would block short waits behind long ones.
RABBIT_RETRY_QUEUES: dict[int, str] = {
    delay_ms: f"extracao.retry.{delay_ms // 1000}s" for delay_ms in RETRY_BACKOFF_MS
}


def rabbit_retry_arguments(delay_ms: int) -> dict[str, Any]:
    """When the TTL expires the message is dead-lettered back to the input queue."""
    return {
        "x-message-ttl": delay_ms,
        "x-dead-letter-exchange": RABBIT_EXCHANGE,
        "x-dead-letter-routing-key": RABBIT_QUEUES[QueueRole.INPUT],
    }


def retry_delay_ms(attempt: int) -> int | None:
    """Wait before trying again after `attempt` failed, or None when the retries are used up."""
    return RETRY_BACKOFF_MS[attempt - 1] if attempt <= len(RETRY_BACKOFF_MS) else None
