"""The worker's queue names and arguments must be the ones in contracts/topology.json.

The backend runs the same check. RabbitMQ refuses a queue declared twice with different
arguments, so a change has to land in the contract and on both sides at once.
"""

import json
from typing import Any

import pytest

from fiscal_worker.messaging import topology
from fiscal_worker.messaging.topology import QueueRole, retry_delay_ms
from support import CONTRACTS

CONTRACT: dict[str, Any] = json.loads((CONTRACTS / "topology.json").read_text(encoding="utf-8"))
ROLE_KEYS = {
    QueueRole.INPUT: "input",
    QueueRole.OUTPUT: "output",
    QueueRole.PROGRESS: "progress",
    QueueRole.DEAD_LETTER: "deadLetter",
}


def test_retry_backoff_matches_the_contract() -> None:
    assert list(topology.RETRY_BACKOFF_MS) == CONTRACT["retryBackoffMs"]


def test_kafka_names_match_the_contract() -> None:
    kafka = CONTRACT["kafka"]

    assert {ROLE_KEYS[role]: name for role, name in topology.KAFKA_TOPICS.items()} | {
        "retry": topology.KAFKA_RETRY_TOPIC
    } == kafka["topics"]
    assert kafka["partitions"] == topology.KAFKA_PARTITIONS
    assert kafka["consumerGroup"] == topology.KAFKA_CONSUMER_GROUP
    assert kafka["notBeforeHeader"] == topology.KAFKA_NOT_BEFORE_HEADER


def test_rabbitmq_names_and_arguments_match_the_contract() -> None:
    rabbitmq = CONTRACT["rabbitmq"]

    assert rabbitmq["exchange"] == topology.RABBIT_EXCHANGE
    assert rabbitmq["prefetch"] == topology.RABBIT_PREFETCH
    assert {ROLE_KEYS[role]: name for role, name in topology.RABBIT_QUEUES.items()} == rabbitmq["queues"]
    assert rabbitmq["retryQueues"] == [
        {"name": name, "arguments": topology.rabbit_retry_arguments(delay_ms)}
        for delay_ms, name in topology.RABBIT_RETRY_QUEUES.items()
    ]


@pytest.mark.parametrize(("attempt", "expected"), [(1, 1000), (2, 2000), (3, 4000), (4, None), (9, None)])
def test_retry_delay_grows_until_the_retries_are_used_up(attempt: int, expected: int | None) -> None:
    assert retry_delay_ms(attempt) == expected
