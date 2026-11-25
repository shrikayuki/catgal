import asyncio
import json
import os
import threading
import time
from uuid import uuid4

import httpx
import pytest
from conftest import TEST_SECRET
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import SecretStr
from redis.asyncio import Redis
from sqlalchemy import delete, select
from test_disconnect_live import running_server

from app.config import Settings
from app.main import create_app
from app.persistence.contracts import PersistentChatRequest, SessionCreate
from app.persistence.database import create_database, session_factory
from app.persistence.models import Conversation, Message
from app.persistence.store import ChatStore
from app.security import compute_signature

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_MYSQL_TEST") != "1", reason="Explicit live MySQL test opt-in"
)
USER = 6


def settings(**updates):
    return Settings(_env_file=None).model_copy(
        update={
            "persistence_enabled": True,
            "service_hmac_secret": SecretStr(TEST_SECRET),
            "chat_api_key": SecretStr("local-fake-provider-key"),
            **updates,
        }
    )


async def clean(store, sessions):
    # Only delete rows belonging to this test's newly created session UUIDs.
    async with store.sessions.begin() as db:
        ids = list(
            await db.scalars(
                select(Message.id)
                .where(Message.session_id.in_(sessions))
                .order_by(Message.id.desc())
            )
        )
        for message_id in ids:
            await db.execute(delete(Message).where(Message.id == message_id))
        await db.execute(delete(Conversation).where(Conversation.session_id.in_(sessions)))
    config = settings()
    if config.redis_url_value:
        redis = Redis.from_url(config.redis_url_value)
        try:
            for session in sessions:
                async for key in redis.scan_iter(match=f"gal:ai:context:{USER}:{session}:v*"):
                    await redis.delete(key)
        finally:
            await redis.aclose()


def request(session, key):
    return PersistentChatRequest(
        user_id=USER,
        session_id=session,
        request_id=str(uuid4()),
        client_message_id=key,
        content="QA test",
    )


@pytest.mark.asyncio
async def test_mysql_serializes_session_and_cross_session_idempotence_races():
    engine = create_database(settings())
    store = ChatStore(session_factory(engine), "qa-model")
    sessions = []
    try:
        for _ in range(2):
            row = await store.create_session(
                SessionCreate(user_id=USER, girl_code="catgal-assistant", title="AI-3 QA")
            )
            sessions.append(row["sessionId"])
        key = "qa-" + uuid4().hex
        results = await asyncio.gather(
            *(store.begin(request(sessions[0], key)) for _ in range(2)), return_exceptions=True
        )
        winner = next(result for result in results if not isinstance(result, Exception))
        assert sum(not isinstance(result, Exception) for result in results) == 1
        assert (
            str(next(result for result in results if isinstance(result, Exception)))
            == "REQUEST_IN_PROGRESS"
        )
        assert await store.finish(winner, "QA answer") is not None
        replay = await store.begin(request(sessions[0], key))
        assert replay.replay and replay.assistant.id == winner.assistant.id
        cross_key = "qa-" + uuid4().hex
        cross = await asyncio.gather(
            *(store.begin(request(session, cross_key)) for session in sessions),
            return_exceptions=True,
        )
        assert sum(not isinstance(result, Exception) for result in cross) == 1
        assert (
            str(next(result for result in cross if isinstance(result, Exception)))
            == "IDEMPOTENCY_CONFLICT"
        )
    finally:
        await clean(store, sessions)
        await engine.dispose()


@pytest.mark.parametrize("stop", ["disconnect", "timeout", "complete"])
def test_live_http_provider_termination_preserves_mysql_state(stop):
    provider = FastAPI()
    provider_closed = threading.Event()

    @provider.post("/v1/chat/completions")
    async def completion():
        async def chunks():
            try:
                while True:
                    payload = {
                        "id": "chatcmpl-qa",
                        "object": "chat.completion.chunk",
                        "choices": [
                            {"index": 0, "delta": {"content": "partial"}, "finish_reason": None}
                        ],
                    }
                    yield f"data: {json.dumps(payload)}\n\n"
                    if stop == "complete":
                        payload["choices"][0].update(delta={}, finish_reason="stop")
                        yield f"data: {json.dumps(payload)}\n\ndata: [DONE]\n\n"
                        return
                    await asyncio.sleep(0.05)
            finally:
                provider_closed.set()

        return StreamingResponse(chunks(), media_type="text/event-stream")

    async def create():
        engine = create_database(settings())
        try:
            row = await ChatStore(session_factory(engine), "qa-model").create_session(
                SessionCreate(user_id=USER, girl_code="catgal-assistant", title="AI-3 HTTP QA")
            )
            return row["sessionId"]
        finally:
            await engine.dispose()

    session = asyncio.run(create())

    async def verify(clean_only=False):
        engine = create_database(settings())
        store = ChatStore(session_factory(engine), "qa-model")
        try:
            if not clean_only:
                for _ in range(100):
                    async with store.sessions() as db:
                        answer = await db.scalar(
                            select(Message).where(
                                Message.session_id == session, Message.role == "assistant"
                            )
                        )
                    if answer is not None and answer.status != "running":
                        break
                    await asyncio.sleep(0.05)
                assert (
                    answer.status
                    == {"complete": "completed", "disconnect": "interrupted", "timeout": "failed"}[
                        stop
                    ]
                )
                assert answer.content.startswith("partial")
                async with store.sessions() as db:
                    row = await db.scalar(
                        select(Conversation).where(Conversation.session_id == session)
                    )
                    assert row.active_message_id is None
                    assert row.message_count == (1 if stop == "complete" else 0)
        finally:
            await clean(store, [session])
            await engine.dispose()

    try:
        with running_server(provider) as provider_url:
            configured = settings(
                chat_base_url=provider_url + "/v1",
                redis_url=None,
                stream_timeout_seconds=0.3 if stop == "timeout" else 5,
            )
            with running_server(create_app(configured)) as address:
                path = "/internal/v1/chat/persistent"
                body = (
                    request(session, "qa-" + uuid4().hex)
                    .model_dump_json(by_alias=True, exclude_none=True)
                    .encode()
                )
                timestamp, nonce = str(int(time.time())), uuid4().hex
                headers = {
                    "Content-Type": "application/json",
                    "X-Catgal-Timestamp": timestamp,
                    "X-Catgal-Nonce": nonce,
                    "X-Catgal-Signature": compute_signature(
                        TEST_SECRET, timestamp, nonce, "POST", path, body
                    ),
                }
                with httpx.Client(timeout=10) as client:
                    with client.stream(
                        "POST", address + path, content=body, headers=headers
                    ) as response:
                        assert response.status_code == 200
                        lines = []
                        for line in response.iter_lines():
                            lines.append(line)
                            if stop == "disconnect" and '"content":"partial"' in line:
                                break
                        if stop == "complete":
                            assert "event: done" in lines
                        elif stop == "timeout":
                            assert any("GENERATION_TIMEOUT" in line for line in lines)
                assert provider_closed.wait(5), "Provider HTTP request must close"
                asyncio.run(verify())
    finally:
        asyncio.run(verify(clean_only=True))
