# 阅读说明
# Python 调用 Java 公共游戏向量目录的有界内部客户端。

import json
import time
from uuid import uuid4

import httpx

from app.security import compute_signature
from app.semantic.client import request_json


class CatalogClient:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.java_tools_base_url,
            timeout=10,
            trust_env=False,
            follow_redirects=False,
            transport=transport,
        )

    async def request(self, operation, payload):
        path = "/internal/ai/vector-catalog/" + operation
        if operation not in {"pending", "export", "ack", "failed", "rebuild", "status"}:
            raise ValueError("Unsupported catalog operation")
        secret = self.settings.hmac_secret_value
        if not secret:
            raise ValueError("Catalog authentication unavailable")
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        timestamp, nonce = str(int(time.time())), uuid4().hex
        data = await request_json(
            self.client,
            "POST",
            path,
            content=body,
            headers={
                "Content-Type": "application/json",
                "X-Catgal-Timestamp": timestamp,
                "X-Catgal-Nonce": nonce,
                "X-Catgal-Signature": compute_signature(
                    secret, timestamp, nonce, "POST", path, body
                ),
            },
        )
        if data.get("code") != 200 or not isinstance(data.get("data"), dict):
            raise ValueError("Invalid public catalog response")
        return data["data"]

    async def aclose(self):
        await self.client.aclose()
