from datetime import UTC, datetime


def iso_now() -> str:
    """ISO 8601 UTC with milliseconds: '2026-10-13T13:20:00.000Z', the format the API stores."""
    now = datetime.now(UTC)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"
