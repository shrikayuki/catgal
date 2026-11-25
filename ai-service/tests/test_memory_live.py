import os
from uuid import uuid4

import pytest
from redis.asyncio import Redis

from app.errors import ConversationBusyError, ConversationLeaseLostError
from app.memory import RedisChatMemory


@pytest.mark.skipif(os.getenv("CATGAL_AI_REDIS_TEST") != "1", reason="Explicit live Redis opt-in")
@pytest.mark.asyncio
async def test_real_redis_serializes_conversations_and_fences_expired_owners() -> None:
    client = Redis.from_url(os.environ["AI_REDIS_URL"], decode_responses=True)
    first = RedisChatMemory(client)
    second = RedisChatMemory(client)
    session = "qa-" + uuid4().hex
    key = first.conversation_key(9223372036854775806, "qa", session)
    lock_key = key + ":generation"
    try:
        async with first.conversation(9223372036854775806, "qa", session) as old_token:
            with pytest.raises(ConversationBusyError):
                async with second.conversation(9223372036854775806, "qa", session):
                    pytest.fail("Second client must not own the same session")
            await first.save_exchange(
                9223372036854775806, "qa", session, "first", "answer", lease_token=old_token
            )
            assert await client.llen(key) == 2
            # Simulate an expired lease replaced by a new request.
            await client.set(lock_key, "replacement-owner", ex=60)
            with pytest.raises(ConversationLeaseLostError):
                await first.save_exchange(
                    9223372036854775806,
                    "qa",
                    session,
                    "stale",
                    "must-not-commit",
                    lease_token=old_token,
                )
        assert await client.get(lock_key) == "replacement-owner"
        assert await client.llen(key) == 2
        assert 0 < await client.ttl(key) <= 604800
    finally:
        await client.delete(key, lock_key)
        await client.aclose()
