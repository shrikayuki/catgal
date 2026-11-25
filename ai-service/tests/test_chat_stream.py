import asyncio

import pytest
from conftest import FakeRuntime, signed_request
from fastapi.testclient import TestClient
from redis.exceptions import ConnectionError as RedisConnectionError

from app.config import Settings
from app.errors import ConversationBusyError, ConversationLeaseLostError, ModelCircuitOpenError
from app.main import create_app


class SlowRuntime:
    def __init__(self) -> None:
        self.closed = False

    async def stream(self, **kwargs):
        try:
            yield "partial"
            await asyncio.sleep(10)
        finally:
            self.closed = True


def test_total_generation_deadline_closes_runtime_and_releases_capacity(settings, chat_payload):
    runtime = SlowRuntime()
    limited = settings.model_copy(update={"stream_timeout_seconds": 0.02})
    with TestClient(create_app(settings=limited, runtime=runtime)) as client:
        response = signed_request(client, chat_payload)
        assert "GENERATION_TIMEOUT" in response.text
        assert "event: done" not in response.text
        assert runtime.closed
        assert client.app.state.concurrency_gate._active == 0


@pytest.mark.parametrize(
    "error,code",
    [
        (ConversationBusyError(), "CONVERSATION_BUSY"),
        (ConversationLeaseLostError(), "CONVERSATION_LEASE_LOST"),
        (ModelCircuitOpenError(), "MODEL_CIRCUIT_OPEN"),
        (RedisConnectionError("private connection details"), "MEMORY_UNAVAILABLE"),
    ],
)
def test_known_failures_have_safe_terminal_codes(settings, chat_payload, error, code):
    with TestClient(create_app(settings=settings, runtime=FakeRuntime(error=error))) as client:
        response = signed_request(client, chat_payload)
        assert code in response.text
        assert "event: done" not in response.text
        assert "private connection details" not in response.text
        assert client.app.state.concurrency_gate._active == 0


def test_capacity_is_checked_inside_the_stream_lifecycle(settings, chat_payload):
    limited = settings.model_copy(update={"max_concurrent_requests": 1})
    with TestClient(create_app(settings=limited, runtime=FakeRuntime())) as client:
        gate = client.app.state.concurrency_gate
        assert client.portal.call(gate.try_acquire)
        response = signed_request(client, chat_payload)
        assert "SERVICE_BUSY" in response.text
        assert "event: done" not in response.text
        assert gate._active == 1
        client.portal.call(gate.release)
        assert gate._active == 0


def test_streams_meta_tokens_and_done(
    client: TestClient,
    runtime: FakeRuntime,
    chat_payload: dict[str, object],
) -> None:
    response = signed_request(client, chat_payload)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: meta" in response.text
    assert 'event: delta\ndata: {"content":"你"}' in response.text
    assert 'event: delta\ndata: {"content":"好"}' in response.text
    assert "event: done" in response.text
    assert runtime.calls == [(7, "shinku", "session-1", "你好", "你是社区助手。")]


def test_stream_hides_upstream_error_details(
    settings: Settings,
    chat_payload: dict[str, object],
) -> None:
    runtime = FakeRuntime(error=RuntimeError("provider secret should not leak"))
    with TestClient(create_app(settings=settings, runtime=runtime)) as client:
        response = signed_request(client, chat_payload, nonce="error-nonce-123456")

    assert response.status_code == 200
    assert "event: error" in response.text
    assert "MODEL_UPSTREAM_ERROR" in response.text
    assert "provider secret" not in response.text


def test_rejects_message_over_limit(
    settings: Settings,
    runtime: FakeRuntime,
    chat_payload: dict[str, object],
) -> None:
    limited_settings = settings.model_copy(update={"max_input_chars": 2})
    with TestClient(create_app(settings=limited_settings, runtime=runtime)) as client:
        response = signed_request(
            client,
            {**chat_payload, "message": "too long"},
            nonce="limit-nonce-123456",
        )

    assert response.status_code == 413
