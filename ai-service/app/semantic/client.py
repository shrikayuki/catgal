# 阅读说明
# embedding 与检索 HTTP 客户端，校验向量数量、维度和数值有效性。

import asyncio
import json
import logging
import math
from urllib.parse import urlsplit

import httpx

logger = logging.getLogger(__name__)
SAFE_PROVIDER_CODES = frozenset(
    {
        "Arrearage",
        "InvalidApiKey",
        "Throttling",
        "QuotaExceeded",
        "AccessDenied",
        "InvalidParameter",
        "InternalError",
        "ServiceUnavailable",
        "RateLimitExceeded",
    }
)


async def request_json(client, method, path, *, limit=2_097_152, **kwargs):
    async with client.stream(method, path, **kwargs) as response:
        chunks, size = [], 0
        async for chunk in response.aiter_bytes():
            size += len(chunk)
            if size > limit:
                raise ValueError("Semantic response exceeds limit")
            chunks.append(chunk)
        body = b"".join(chunks)
        if not response.is_success:
            # Preserve bounded, decoded error bytes after the network stream is closed.
            # Dropping compression headers prevents decoding this already-decoded body twice.
            headers = {
                key: value
                for key, value in response.headers.items()
                if key.lower() not in {"content-encoding", "content-length", "transfer-encoding"}
            }
            error_response = httpx.Response(
                response.status_code, headers=headers, content=body, request=response.request
            )
            error_response.raise_for_status()
        return json.loads(body)


def vector(value, dimensions):
    if not isinstance(value, list) or len(value) != dimensions:
        raise ValueError("Embedding dimension mismatch")
    if any(
        isinstance(item, bool) or not isinstance(item, (int, float)) or not math.isfinite(item)
        for item in value
    ):
        raise ValueError("Invalid embedding values")
    if not any(item != 0 for item in value):
        raise ValueError("Empty embedding magnitude")
    return value


class EmbeddingClient:
    def __init__(self, settings, transport=None):
        self.settings = settings
        endpoint = urlsplit(settings.embedding_base_url)
        if (
            not endpoint.hostname
            or endpoint.username
            or endpoint.password
            or endpoint.query
            or endpoint.fragment
            or (endpoint.scheme != "https" and settings.app_env != "test")
        ):
            raise ValueError("Invalid embedding endpoint configuration")
        key = settings.embedding_api_key
        self.client = httpx.AsyncClient(
            base_url=settings.embedding_base_url.rstrip("/") + "/",
            headers={"Authorization": "Bearer " + key.get_secret_value()} if key else {},
            timeout=settings.embedding_timeout_seconds,
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )
        self.gate = asyncio.Semaphore(2)

    async def embed(self, texts):
        if not self.settings.embedding_api_key:
            raise ValueError("Platform embedding key unavailable")
        if not 1 <= len(texts) <= 10 or any(not text.strip() or len(text) > 2000 for text in texts):
            raise ValueError("Embedding input exceeds budget")
        try:
            async with self.gate:
                data = await request_json(
                    self.client,
                    "POST",
                    "embeddings",
                    json={
                        "model": self.settings.embedding_model,
                        "input": texts,
                        "dimensions": self.settings.embedding_dimensions,
                        "encoding_format": "float",
                    },
                )
            rows = data.get("data")
            if not isinstance(rows, list) or len(rows) != len(texts):
                raise ValueError("Invalid embedding batch")
            indexed = {}
            for row in rows:
                index = row.get("index")
                if type(index) is not int or not 0 <= index < len(texts) or index in indexed:
                    raise ValueError("Invalid embedding index")
                indexed[index] = vector(row.get("embedding"), self.settings.embedding_dimensions)
            return [indexed[index] for index in range(len(texts))]
        except Exception as exc:
            status, code = None, "UPSTREAM_UNAVAILABLE"
            if isinstance(exc, httpx.HTTPStatusError):
                status, code = exc.response.status_code, "UPSTREAM_REJECTED"
                try:
                    provider_code = exc.response.json().get("error", {}).get("code")
                except (ValueError, AttributeError, TypeError):
                    provider_code = None
                if isinstance(provider_code, str) and provider_code in SAFE_PROVIDER_CODES:
                    code = provider_code
            logger.warning(
                "Platform embedding unavailable: type=%s status=%s code=%s",
                type(exc).__name__,
                status,
                code,
            )
            raise

    async def aclose(self):
        await self.client.aclose()
