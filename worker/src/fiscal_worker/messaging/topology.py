"""Queue and topic names for both brokers (BACKEND.md §5.2). They are part of the frontend contract.

The backend declares the same RabbitMQ objects. Arguments must match exactly, or the broker
answers 406 PRECONDITION_FAILED to whoever declares second.
"""

from enum import StrEnum
from typing import Any


class QueueRole(StrEnum):
    INPUT = "input"
    OUTPUT = "output"
    PROGRESS = "progress"
    DEAD_LETTER = "dead_letter"


KAFKA_TOPICS: dict[QueueRole, str] = {
    QueueRole.INPUT: "documentos.recebidos",
    QueueRole.OUTPUT: "documentos.extraidos",
    QueueRole.PROGRESS: "documentos.progresso",
    QueueRole.DEAD_LETTER: "documentos.recebidos.DLT",
}
KAFKA_RETRY_TOPIC = "documentos.recebidos-retry"
KAFKA_CONSUMER_GROUP = "extrator-py"
KAFKA_PARTITIONS = 3
KAFKA_REPLICATION_FACTOR = 1

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
    1000: "extracao.retry.1s",
    2000: "extracao.retry.2s",
    4000: "extracao.retry.4s",
}


def rabbit_retry_arguments(delay_ms: int) -> dict[str, Any]:
    """When the TTL expires the message is dead-lettered back to the input queue."""
    return {
        "x-message-ttl": delay_ms,
        "x-dead-letter-exchange": RABBIT_EXCHANGE,
        "x-dead-letter-routing-key": RABBIT_QUEUES[QueueRole.INPUT],
    }
