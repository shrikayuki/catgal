import json
import time
from collections.abc import AsyncIterator

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings
from app.main import create_app
from app.security import compute_signature

TEST_SECRET = "test-secret-that-is-long-enough-for-hmac"


class FakeRuntime:
    def __init__(self, chunks: list[str] | None = None, error: Exception | None = None) -> None:
        self.chunks = chunks or ["你", "好"]
        self.error = error
        self.calls: list[tuple[int, str, str, str, str]] = []

    async def stream(
        self,
        *,
        user_id: int,
        girl_code: str,
        session_id: str,
        message: str,
        system_prompt: str,
    ) -> AsyncIterator[str]:
        self.calls.append((user_id, girl_code, session_id, message, system_prompt))
        if self.error:
            raise self.error
        for chunk in self.chunks:
            yield chunk


@pytest.fixture
def settings() -> Settings:
    return Settings(
        app_env="test",
        service_hmac_secret=SecretStr(TEST_SECRET),
        chat_api_key=SecretStr("test-provider-key"),
        redis_url=SecretStr("redis://127.0.0.1:1/0"),
        _env_file=None,
    )


@pytest.fixture
def runtime() -> FakeRuntime:
    return FakeRuntime()


@pytest.fixture
def client(settings: Settings, runtime: FakeRuntime) -> AsyncIterator[TestClient]:
    with TestClient(create_app(settings=settings, runtime=runtime)) as test_client:
        yield test_client


def signed_request(
    client: TestClient,
    payload: dict[str, object],
    *,
    nonce: str = "nonce-12345678",
    secret: str = TEST_SECRET,
):
    path = "/internal/v1/chat/stream"
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    timestamp = str(int(time.time()))
    signature = compute_signature(secret, timestamp, nonce, "POST", path, body)
    return client.post(
        path,
        content=body,
        headers={
            "Content-Type": "application/json",
            "X-Catgal-Timestamp": timestamp,
            "X-Catgal-Nonce": nonce,
            "X-Catgal-Signature": signature,
        },
    )


@pytest.fixture
def chat_payload() -> dict[str, object]:
    return {
        "requestId": "request-12345678",
        "userId": 7,
        "sessionId": "session-1",
        "girlCode": "shinku",
        "message": "你好",
        "systemPrompt": "你是社区助手。",
    }
