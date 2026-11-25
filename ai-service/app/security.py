# 阅读说明
# Java 到 Python 的内部 HMAC 校验与当前进程 nonce 防重放。
# 签名规范与 Java AiServiceRequestSigner 对齐，内部身份取已验证正文。

import asyncio
import hashlib
import hmac
import time
from collections.abc import Callable

from fastapi import HTTPException, Request, status

from app.config import Settings

TIMESTAMP_HEADER = "X-Catgal-Timestamp"
NONCE_HEADER = "X-Catgal-Nonce"
SIGNATURE_HEADER = "X-Catgal-Signature"


def body_sha256(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


# 规范化顺序必须与 Java 一致，签名的正文以实际字节计算 SHA-256。
def canonical_request(timestamp: str, nonce: str, method: str, path: str, body: bytes) -> str:
    return "\n".join((timestamp, nonce, method.upper(), path, body_sha256(body)))


def compute_signature(
    secret: str,
    timestamp: str,
    nonce: str,
    method: str,
    path: str,
    body: bytes,
) -> str:
    payload = canonical_request(timestamp, nonce, method, path, body).encode()
    return hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest()


class NonceReplayGuard:
    def __init__(self, now: Callable[[], float] = time.time) -> None:
        self._now = now
        self._nonces: dict[str, float] = {}
        self._lock = asyncio.Lock()

    # 在进程内 asyncio.Lock 保护下清理过期 nonce，再拒绝已接收过的请求。
    async def accept_once(self, nonce: str, ttl_seconds: int) -> bool:
        now = self._now()
        async with self._lock:
            self._nonces = {key: expiry for key, expiry in self._nonces.items() if expiry > now}
            if nonce in self._nonces:
                return False
            self._nonces[nonce] = now + ttl_seconds
            return True


# 先校验时间和 HMAC，再占用 nonce，保证校验失败的请求无法消耗合法重放标识。
async def verify_internal_request(request: Request) -> None:
    settings: Settings = request.app.state.settings
    secret = settings.hmac_secret_value
    if not secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI service authentication is not configured",
        )

    timestamp = request.headers.get(TIMESTAMP_HEADER, "")
    nonce = request.headers.get(NONCE_HEADER, "")
    supplied_signature = request.headers.get(SIGNATURE_HEADER, "")
    if not timestamp or not nonce or not supplied_signature:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")

    try:
        request_time = int(timestamp)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid signature",
        ) from exc

    now = int(time.time())
    if abs(now - request_time) > settings.request_max_age_seconds:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")

    body = await request.body()
    expected = compute_signature(
        secret,
        timestamp,
        nonce,
        request.method,
        request.url.path,
        body,
    )
    if not hmac.compare_digest(expected, supplied_signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")

    replay_guard: NonceReplayGuard = request.app.state.replay_guard
    # Future-dated accepted requests remain valid for up to two clock windows.
    if not await replay_guard.accept_once(nonce, 2 * settings.request_max_age_seconds + 1):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")
