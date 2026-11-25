from unittest.mock import AsyncMock, MagicMock

import pytest

from app.errors import ConversationBusyError, ConversationLeaseLostError
from app.memory import RedisChatMemory


@pytest.mark.asyncio
async def test_busy_conversation_is_rejected_without_releasing_another_owner() -> None:
    client = MagicMock()
    client.set = AsyncMock(return_value=False)
    client.eval = AsyncMock()
    memory = RedisChatMemory(client)
    with pytest.raises(ConversationBusyError):
        async with memory.conversation(7, "emily", "session-1"):
            pytest.fail("Busy conversation must not start")
    client.eval.assert_not_awaited()


@pytest.mark.asyncio
async def test_conversation_release_is_token_checked_even_when_generation_fails() -> None:
    client = MagicMock()
    client.set = AsyncMock(return_value=True)
    client.eval = AsyncMock(return_value=1)
    memory = RedisChatMemory(client)
    with pytest.raises(ValueError):
        async with memory.conversation(7, "emily", "session-1") as token:
            raise ValueError("fake generation failure")
    assert client.set.await_args.kwargs == {"nx": True, "px": 105000}
    assert client.eval.await_args.args[1:] == (
        1,
        "gal:chat:memory:user:7:girl:emily:session:session-1:generation",
        token,
    )


@pytest.mark.asyncio
async def test_lost_lease_rejects_stale_exchange_commit() -> None:
    client = MagicMock()
    client.eval = AsyncMock(return_value=0)
    memory = RedisChatMemory(client)
    with pytest.raises(ConversationLeaseLostError):
        await memory.save_exchange(7, "emily", "session-1", "hello", "world", lease_token="old")
    client.pipeline.assert_not_called()
    assert client.eval.await_args.args[-5:] == ("old", 20, 604800, "user:hello", "assistant:world")


@pytest.mark.asyncio
async def test_load_keeps_legacy_java_memory_format() -> None:
    client = MagicMock()
    client.lrange = AsyncMock(
        return_value=[
            "user:你好:还在吗",
            "assistant:我在",
            "system:ignored",
            "broken",
        ]
    )
    memory = RedisChatMemory(client)

    result = await memory.load(7, "emily", "session-1")

    assert result == [
        {"role": "user", "content": "你好:还在吗"},
        {"role": "assistant", "content": "我在"},
    ]
    client.lrange.assert_awaited_once_with(
        "gal:chat:memory:user:7:girl:emily:session:session-1",
        0,
        -1,
    )


@pytest.mark.asyncio
async def test_save_exchange_is_atomic_and_bounded() -> None:
    pipeline = MagicMock()
    pipeline.__aenter__ = AsyncMock(return_value=pipeline)
    pipeline.__aexit__ = AsyncMock(return_value=None)
    pipeline.execute = AsyncMock(return_value=[])
    client = MagicMock()
    client.pipeline.return_value = pipeline
    memory = RedisChatMemory(client, max_history=20, ttl_seconds=60)

    await memory.save_exchange(7, "emily", "session-1", "你好", "你好呀")

    key = "gal:chat:memory:user:7:girl:emily:session:session-1"
    pipeline.rpush.assert_called_once_with(key, "user:你好", "assistant:你好呀")
    pipeline.ltrim.assert_called_once_with(key, -20, -1)
    pipeline.expire.assert_called_once_with(key, 60)
    pipeline.execute.assert_awaited_once()
