import json
from contextlib import asynccontextmanager

import httpx
import pytest
from langchain_core.messages import AIMessageChunk
from pydantic import SecretStr

from app.config import Settings
from app.memory import HistoryMessage
from app.runtime import LangGraphChatRuntime, message_text


class NoopMemory:
    def __init__(self) -> None:
        self.saved = []
        self.released = False

    @asynccontextmanager
    async def conversation(self, user_id: int, girl_code: str, session_id: str):
        try:
            yield "lease-token"
        finally:
            self.released = True

    async def load(self, user_id: int, girl_code: str, session_id: str) -> list[HistoryMessage]:
        return []

    async def save_exchange(
        self,
        user_id: int,
        girl_code: str,
        session_id: str,
        user_message: str,
        assistant_message: str,
        lease_token: str | None = None,
    ) -> None:
        self.saved.append((user_message, assistant_message, lease_token))

    async def health(self) -> bool:
        return True

    async def close(self) -> None:
        return None


def test_langgraph_runtime_builds_with_locked_dependencies() -> None:
    settings = Settings(
        chat_api_key=SecretStr("test-provider-key"),
        chat_base_url="http://127.0.0.1:1/v1",
        _env_file=None,
    )

    runtime = LangGraphChatRuntime(settings, NoopMemory())

    assert runtime is not None


def test_message_text_handles_standard_content_blocks() -> None:
    content = [
        {"type": "text", "text": "你"},
        {"type": "reasoning", "text": "hidden"},
        {"type": "text", "text": "好"},
    ]

    assert message_text(content) == "你好"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("base_url", "deepseek"),
    [
        ("https://api.deepseek.com", True),
        ("https://api.deepseek.com/v1", True),
        ("https://dashscope.aliyuncs.com/compatible-mode/v1", False),
        ("https://api.deepseek.com.example.org/v1", False),
    ],
)
async def test_model_request_preserves_provider_token_limits(monkeypatch, base_url, deepseek):
    requests = []

    async def send(_client, request, **_kwargs):
        requests.append(json.loads(request.content))
        return httpx.Response(
            200,
            request=request,
            json={
                "id": "test-completion",
                "object": "chat.completion",
                "created": 1,
                "model": "test-model",
                "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "OK"},
                     "finish_reason": "stop"}
                ],
            },
        )

    monkeypatch.setattr(httpx.AsyncClient, "send", send)
    runtime = LangGraphChatRuntime(
        Settings(
            chat_api_key=SecretStr("test-key"),
            chat_base_url=base_url,
            chat_model="test-model",
            model_max_completion_tokens=42,
            _env_file=None,
        ),
        NoopMemory(),
    )
    try:
        response = await runtime._chat_model.ainvoke("test")
        assert response.content == "OK"
        assert len(requests) == 1
        body = requests[0]
        if deepseek:
            assert body["max_tokens"] == 42
            assert body["thinking"] == {"type": "disabled"}
            assert "max_completion_tokens" not in body
        else:
            assert body["max_completion_tokens"] == 42
            assert "max_tokens" not in body
            assert "thinking" not in body
    finally:
        await runtime.aclose()


class FakeGraph:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.closed = False

    async def astream(self, *args, **kwargs):
        try:
            yield {
                "type": "messages",
                "data": (AIMessageChunk(content="你好"), {"langgraph_node": "chat_model"}),
            }
            if self.fail:
                raise RuntimeError("fake upstream failure")
        finally:
            self.closed = True


def fake_runtime(memory: NoopMemory, graph: FakeGraph) -> LangGraphChatRuntime:
    runtime = LangGraphChatRuntime(
        Settings(chat_api_key=SecretStr("test-key"), _env_file=None), memory
    )
    runtime._graph = graph
    return runtime


def stream(runtime: LangGraphChatRuntime):
    return runtime.stream(
        user_id=7, girl_code="emily", session_id="session-1", message="你好", system_prompt="system"
    )


@pytest.mark.asyncio
async def test_completed_generation_commits_under_its_lease() -> None:
    memory, graph = NoopMemory(), FakeGraph()
    assert [chunk async for chunk in stream(fake_runtime(memory, graph))] == ["你好"]
    assert memory.saved == [("你好", "你好", "lease-token")]
    assert graph.closed and memory.released


@pytest.mark.asyncio
async def test_failed_generation_does_not_save_partial_exchange() -> None:
    memory, graph = NoopMemory(), FakeGraph(fail=True)
    with pytest.raises(RuntimeError):
        async for _ in stream(fake_runtime(memory, graph)):
            pass
    assert memory.saved == []
    assert graph.closed and memory.released


@pytest.mark.asyncio
async def test_closed_stream_closes_graph_and_releases_conversation() -> None:
    memory, graph = NoopMemory(), FakeGraph()
    iterator = stream(fake_runtime(memory, graph))
    assert await anext(iterator) == "你好"
    await iterator.aclose()
    assert memory.saved == []
    assert graph.closed and memory.released
