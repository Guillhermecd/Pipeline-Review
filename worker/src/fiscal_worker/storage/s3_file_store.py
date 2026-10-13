"""Reads uploaded files from MinIO/S3. The worker's credential is read-only."""

import asyncio
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from fiscal_worker.domain.errors import PermanentError, TransientError

_MISSING_OBJECT_CODES = {"NoSuchKey", "404", "NotFound"}
_TIMEOUT_SECONDS = 10


class S3FileStore:
    def __init__(self, endpoint: str, region: str, access_key_id: str, secret_access_key: str, bucket: str) -> None:
        self._bucket = bucket
        self._client: Any = boto3.client(
            "s3",
            endpoint_url=endpoint,
            region_name=region,
            aws_access_key_id=access_key_id,
            aws_secret_access_key=secret_access_key,
            # The broker's retry policy owns retries; stacking the SDK's on top would multiply them.
            config=Config(
                s3={"addressing_style": "path"},
                retries={"max_attempts": 1},
                connect_timeout=_TIMEOUT_SECONDS,
                read_timeout=_TIMEOUT_SECONDS,
            ),
        )

    async def download(self, key: str) -> bytes:
        return await asyncio.to_thread(self._download, key)

    def _download(self, key: str) -> bytes:
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=key)
            content: bytes = response["Body"].read()
        except ClientError as error:
            if error.response.get("Error", {}).get("Code") in _MISSING_OBJECT_CODES:
                raise PermanentError(f"arquivo não encontrado no storage: {key}") from error
            raise TransientError("MinIO indisponível") from error
        except BotoCoreError as error:
            raise TransientError("MinIO indisponível") from error
        return content
