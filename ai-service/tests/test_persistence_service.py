import asyncio
import json
import time
from uuid import uuid4

import httpx
import pytest
from conftest import TEST_SECRET
from sqlalchemy import select
from test_persistence_store import chat, conversation
from test_persistence_store import store as store

from app.main import create_app
from app.persistence.context import DurableContext
from app.persistence.contracts import MessageQuery
from app.persistence.models import Message
from app.persistence.service import PersistentChat
from app.security import compute_signature


class Runtime:
    def __init__(self, error=None, slow=False):
        self.calls = []
        self.error = error
        self.slow = slow
        self.closed = False

    async def generate(self, history, message, prompt):
        self.calls.append((history, message, prompt))
        try:
            yield "partial"
            if self.error:
                raise self.error
            if self.slow:
                await asyncio.sleep(10)
            yield " answer"
        finally:
            self.closed = True


async def collect(service, request):
    return [event async for event in service.events(request)]


@pytest.mark.asyncio
async def test_completed_replay_never_calls_model_again_and_has_same_message_id(store):
    session = await conversation(store)
    runtime = Runtime()
    service = PersistentChat(store, DurableContext(store), runtime, 5)
    first = await collect(service, chat(session))
    replay = await collect(service, chat(session))
    assert len(runtime.calls) == 1
    assert first[0][1]["messageId"] == replay[0][1]["messageId"]
    assert replay[0][1]["replayed"] is True
    assert replay[1] == ("delta", {"content": "partial answer"})
    assert first[-1][0] == replay[-1][0] == "done"
    following = await collect(service, chat(session, "next", "follow up"))
    assert following[-1][0] == "done"
    assert runtime.calls[-1][0] == [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "partial answer"},
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["error", "timeout", "disconnect"])
async def test_partial_generations_are_durable_and_never_implicitly_retried(store, failure):
    session = await conversation(store)
    runtime = Runtime(
        error=RuntimeError("private-provider-detail") if failure == "error" else None,
        slow=failure == "timeout",
    )
    service = PersistentChat(store, DurableContext(store), runtime, 0.04)
    request = chat(session)
    if failure == "disconnect":
        stream = service.events(request)
        assert (await anext(stream))[0] == "meta"
        assert (await anext(stream))[0] == "delta"
        await stream.aclose()
    else:
        result = await collect(service, request)
        assert result[-1][0] == "error"
        assert "private-provider-detail" not in str(result)
    assert runtime.closed
    rows = await store.list_messages(MessageQuery(user_id=7, session_id=session))
    answer = rows["items"][-1]
    assert answer["content"] == "partial"
    assert answer["status"] == ("interrupted" if failure == "disconnect" else "failed")
    replay = await collect(service, request)
    assert replay[-1][0] == "error" and len(runtime.calls) == 1
    assert replay[0][1]["replayed"] is True


@pytest.mark.asyncio
async def test_deadline_also_covers_context_loading(store):
    class SlowContext:
        async def load(self, _prepared):
            await asyncio.sleep(10)

    session = await conversation(store)
    runtime = Runtime()
    result = await collect(PersistentChat(store, SlowContext(), runtime, 0.02), chat(session))
    assert result[-1][1]["code"] == "GENERATION_TIMEOUT"
    assert runtime.calls == []


@pytest.mark.asyncio
async def test_cancellation_before_first_token_releases_durable_lease(store):
    session = await conversation(store)
    stream = PersistentChat(store, DurableContext(store), Runtime(), 5).events(chat(session))
    await anext(stream)
    await stream.aclose()
    prepared = await store.begin(chat(session, "another"))
    async with store.sessions() as db:
        first = await db.scalar(select(Message).where(Message.id == prepared.assistant.id - 2))
        assert first.status == "interrupted"


async def post(client, path, payload, signed=True):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    timestamp, nonce = str(int(time.time())), uuid4().hex
    headers = {"Content-Type": "application/json"}
    if signed:
        headers.update(
            {
                "X-Catgal-Timestamp": timestamp,
                "X-Catgal-Nonce": nonce,
                "X-Catgal-Signature": compute_signature(
                    TEST_SECRET, timestamp, nonce, "POST", path, body
                ),
            }
        )
    return await client.post(path, content=body, headers=headers)


@pytest.mark.asyncio
async def test_internal_endpoints_require_hmac_and_enforce_user_ownership(store, settings):
    configured = settings.model_copy(update={"persistence_enabled": True, "redis_url": None})
    app = create_app(configured, runtime=Runtime(), store=store)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            path = "/internal/v1/sessions/create"
            payload = {"userId": 7, "girlCode": "emily"}
            assert (await post(client, path, payload, signed=False)).status_code == 401
            created = await post(client, path, payload)
            assert created.status_code == 200
            session = created.json()["sessionId"]
            other = await post(
                client, "/internal/v1/messages/list", {"userId": 8, "sessionId": session}
            )
            assert other.status_code == 404 and other.json()["code"] == "SESSION_NOT_FOUND"
            request = chat(session).model_dump(by_alias=True, exclude_none=True)
            sent = await post(client, "/internal/v1/chat/persistent", request)
            assert "event: done" in sent.text
            replay = await post(client, "/internal/v1/chat/persistent", request)
            assert '"replayed":true' in replay.text
            assert len(app.state.runtime.calls) == 1
            old = await post(
                client,
                "/internal/v1/chat/stream",
                {
                    "userId": 7,
                    "requestId": "req-12345678",
                    "girlCode": "emily",
                    "sessionId": session,
                    "message": "hello",
                    "systemPrompt": "override",
                },
            )
            assert old.status_code == 503 and old.json()["code"] == "PERSISTENCE_REQUIRED"


@pytest.mark.asyncio
async def test_new_endpoints_are_disabled_until_migration_flag_enabled(settings):
    app = create_app(settings, runtime=Runtime())
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await post(
                client, "/internal/v1/sessions/create", {"userId": 7, "girlCode": "emily"}
            )
            assert response.status_code == 503
