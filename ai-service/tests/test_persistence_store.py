from datetime import timedelta
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
import pytest_asyncio
from redis.exceptions import ConnectionError as RedisConnectionError
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.persistence.context import DurableContext
from app.persistence.contracts import (
    MessageQuery,
    PersistenceError,
    PersistentChatRequest,
    SessionCreate,
    SessionQuery,
    SessionRequest,
)
from app.persistence.models import Base, Character, Conversation, Message
from app.persistence.store import ChatStore


@pytest_asyncio.fixture
async def store():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    async with sessions.begin() as db:
        db.add(Character(girl_code="emily", name="QA", system_prompt="QA system", status=1))
    yield ChatStore(sessions, "qa-model")
    await engine.dispose()


async def conversation(store, user=7):
    result = await store.create_session(SessionCreate(user_id=user, girl_code="emily"))
    return result["sessionId"]


def chat(session, key="key-1", content="hello", user=7, regenerate=None):
    return PersistentChatRequest(
        user_id=user,
        session_id=session,
        request_id=str(uuid4()),
        client_message_id=key,
        content=content,
        regenerate_message_id=regenerate,
    )


async def count_messages(store):
    async with store.sessions() as db:
        return await db.scalar(select(func.count()).select_from(Message))


@pytest.mark.asyncio
async def test_same_character_allows_multiple_sessions_and_user_scoped_lists(store):
    first, second, other = (
        await conversation(store),
        await conversation(store),
        await conversation(store, 8),
    )
    assert len({first, second, other}) == 3
    rows = await store.list_sessions(SessionQuery(user_id=7, limit=1))
    assert rows["hasMore"] and len(rows["items"]) == 1
    more = await store.list_sessions(SessionQuery(user_id=7, before_id=int(rows["nextCursor"])))
    assert len(more["items"]) == 1 and not more["hasMore"]
    assert rows["items"][0]["sessionId"] != more["items"][0]["sessionId"]


@pytest.mark.asyncio
async def test_duplicate_request_replays_completed_answer_without_new_rows(store):
    session = await conversation(store)
    request = chat(session)
    prepared = await store.begin(request)
    assert await count_messages(store) == 2
    with pytest.raises(PersistenceError, match="REQUEST_IN_PROGRESS"):
        await store.begin(request)
    with pytest.raises(PersistenceError, match="CONVERSATION_BUSY"):
        await store.begin(chat(session, "another-key"))
    assert await store.finish(prepared, "answer") == 1
    replay = await store.begin(request)
    assert replay.replay and replay.assistant.id == prepared.assistant.id
    assert replay.assistant.content == "answer"
    assert await count_messages(store) == 2


@pytest.mark.asyncio
async def test_idempotency_key_cannot_change_body_or_session(store):
    first, second = await conversation(store), await conversation(store)
    prepared = await store.begin(chat(first))
    await store.finish(prepared, "answer")
    for request in [chat(first, content="changed"), chat(second)]:
        with pytest.raises(PersistenceError, match="IDEMPOTENCY_CONFLICT"):
            await store.begin(request)
    assert await count_messages(store) == 2


@pytest.mark.asyncio
async def test_other_users_cannot_get_read_delete_or_generate_in_a_session(store):
    session = await conversation(store)
    for operation in [
        store.get_session(SessionRequest(user_id=8, session_id=session)),
        store.list_messages(MessageQuery(user_id=8, session_id=session)),
        store.delete_session(SessionRequest(user_id=8, session_id=session)),
        store.begin(chat(session, user=8)),
    ]:
        with pytest.raises(PersistenceError, match="SESSION_NOT_FOUND"):
            await operation
    assert await count_messages(store) == 0


@pytest.mark.asyncio
async def test_expired_request_recovers_and_old_worker_cannot_commit(store):
    session = await conversation(store)
    request = chat(session)
    old = await store.begin(request)
    await store.checkpoint(old, "partial")
    async with store.sessions.begin() as db:
        row = await db.scalar(select(Conversation).where(Conversation.session_id == session))
        row.active_lease_until = await store._now(db) - timedelta(seconds=2)
    replay = await store.begin(request)
    assert replay.replay and replay.assistant.status == "interrupted"
    assert replay.assistant.content == "partial"
    new = await store.begin(chat(session, "new-key"))
    assert await store.finish(old, "late old answer") is None
    assert await store.finish(new, "new answer") == 1


@pytest.mark.asyncio
async def test_regeneration_reuses_user_message_and_context_uses_latest_completed_answer(store):
    session = await conversation(store)
    first = await store.begin(chat(session))
    await store.finish(first, "old answer")
    regenerate = await store.begin(chat(session, "regen-key", None, regenerate=first.assistant.id))
    context = DurableContext(store)
    assert await context.load(regenerate) == []
    await store.finish(regenerate, "replacement answer")
    details = await store.get_session(SessionRequest(user_id=7, session_id=session))
    assert details["messageCount"] == 1
    assert await count_messages(store) == 3
    next_turn = await store.begin(chat(session, "next-key", "next question"))
    assert await context.load(next_turn) == [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "replacement answer"},
    ]
    await store.finish(next_turn, "next answer")
    with pytest.raises(PersistenceError, match="REGENERATION_CONFLICT"):
        await store.begin(chat(session, "old-regen", None, regenerate=first.assistant.id))


@pytest.mark.asyncio
async def test_failed_regeneration_cache_does_not_hide_the_previous_completed_turn(store):
    class Cache:
        def __init__(self):
            self.data = {}

        async def get(self, key):
            return self.data.get(key)

        async def set(self, key, value, ex):
            self.data[key] = value

    session = await conversation(store)
    first = await store.begin(chat(session))
    await store.finish(first, "old answer")
    context = DurableContext(store, Cache())
    regenerate = await store.begin(chat(session, "regen", None, regenerate=first.assistant.id))
    assert await context.load(regenerate) == []
    await store.finish(regenerate, "partial", "failed", "MODEL_UPSTREAM_ERROR")
    next_turn = await store.begin(chat(session, "next", "next question"))
    assert await context.load(next_turn) == [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "old answer"},
    ]


@pytest.mark.asyncio
async def test_redis_loss_reconstructs_context_from_database(store):
    session = await conversation(store)
    first = await store.begin(chat(session))
    await store.finish(first, "answer")
    next_turn = await store.begin(chat(session, "next", "next question"))
    cache = AsyncMock()
    cache.get.side_effect = RedisConnectionError()
    cache.set.side_effect = RedisConnectionError()
    assert await DurableContext(store, cache).load(next_turn) == [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "answer"},
    ]


@pytest.mark.asyncio
async def test_delete_is_owned_idempotent_and_fences_running_generation(store):
    session = await conversation(store)
    prepared = await store.begin(chat(session))
    request = SessionRequest(user_id=7, session_id=session)
    assert await store.delete_session(request) == {"deleted": True}
    assert await store.delete_session(request) == {"deleted": True}
    assert await store.finish(prepared, "late answer") is None
    with pytest.raises(PersistenceError, match="SESSION_NOT_FOUND"):
        await store.list_messages(MessageQuery(user_id=7, session_id=session))


@pytest.mark.asyncio
async def test_message_cursor_is_chronological_and_has_no_duplicate_ids(store):
    session = await conversation(store)
    for index in range(3):
        prepared = await store.begin(chat(session, "key-" + str(index)))
        await store.finish(prepared, "answer-" + str(index))
    seen = []
    cursor = None
    while True:
        page = await store.list_messages(
            MessageQuery(user_id=7, session_id=session, before_id=cursor, limit=2)
        )
        ids = [int(row["id"]) for row in page["items"]]
        assert ids == sorted(ids)
        seen.extend(ids)
        if not page["hasMore"]:
            break
        cursor = int(page["nextCursor"])
    assert len(seen) == len(set(seen)) == 6
