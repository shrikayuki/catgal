import asyncio
import base64
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import httpx
import pytest
from conftest import TEST_SECRET
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from pydantic import SecretStr
from sqlalchemy import delete, select
from test_disconnect_live import running_server

from app.bots.contracts import (
    BotCreate,
    BotUpdate,
    CredentialCreate,
    CredentialRevoke,
    CredentialRotate,
)
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.store import BotStore
from app.config import Settings
from app.main import create_app
from app.persistence.contracts import PersistentChatRequest, SessionCreate
from app.persistence.database import create_database, session_factory
from app.persistence.models import Conversation, Message
from app.persistence.store import ChatStore
from app.security import compute_signature

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_BOTS_TEST") != "1", reason="Explicit live V10 MySQL opt-in"
)


async def with_store(settings, callback):
    engine = create_database(settings)
    sessions = session_factory(engine)
    bots = BotStore(sessions, settings)
    try:
        return await callback(ChatStore(sessions, "qa-model", bots=bots), bots)
    finally:
        await engine.dispose()


def test_real_mysql_and_http_route_each_bot_to_its_own_key_and_prompt_version():
    provider = FastAPI()
    calls = []
    rows = {"bots": [], "credentials": [], "sessions": []}

    @provider.post("/v1/chat/completions")
    async def completion(request: Request):
        payload = await request.json()
        calls.append((request.headers["authorization"], payload["messages"][0]["content"]))

        async def chunks():
            payload = {
                "id": "chatcmpl-qa",
                "object": "chat.completion.chunk",
                "choices": [{"index": 0, "delta": {"content": "QA answer"}, "finish_reason": None}],
            }
            yield f"data: {json.dumps(payload)}\n\n"
            payload["choices"][0].update(delta={}, finish_reason="stop")
            yield f"data: {json.dumps(payload)}\n\ndata: [DONE]\n\n"

        return StreamingResponse(chunks(), media_type="text/event-stream")

    async def prepare(store, bots):
        for key in ("qa-alpha-key-1111", "qa-beta-key-2222"):
            credential = await bots.create_credential(
                CredentialCreate(
                    user_id=6,
                    provider_code="dashscope",
                    label="QA-" + uuid4().hex,
                    api_key=SecretStr(key),
                )
            )
            rows["credentials"].append(int(credential["id"]))
            bot = await bots.create_bot(
                BotCreate(
                    user_id=6,
                    name="QA temporary",
                    persona="persona-v1",
                    credential_id=int(credential["id"]),
                    model="qa-model",
                )
            )
            rows["bots"].append(int(bot["id"]))
            session = await store.create_session(SessionCreate(user_id=6, bot_id=int(bot["id"])))
            rows["sessions"].append(session["sessionId"])

    async def clean(store, _bots):
        async with store.sessions.begin() as db:
            ids = list(
                await db.scalars(
                    select(Message.id)
                    .where(Message.session_id.in_(rows["sessions"]))
                    .order_by(Message.id.desc())
                )
            )
            for message_id in ids:
                await db.execute(delete(Message).where(Message.id == message_id))
            await db.execute(
                delete(Conversation).where(Conversation.session_id.in_(rows["sessions"]))
            )
            await db.execute(delete(PromptVersion).where(PromptVersion.bot_id.in_(rows["bots"])))
            await db.execute(delete(Bot).where(Bot.id.in_(rows["bots"])))
            await db.execute(delete(Credential).where(Credential.id.in_(rows["credentials"])))

    with running_server(provider) as provider_url:
        configured = Settings(_env_file=None).model_copy(
            update={
                "app_env": "test",
                "persistence_enabled": True,
                "personalization_enabled": True,
                "service_hmac_secret": SecretStr(TEST_SECRET),
                "chat_api_key": None,
                "redis_url": None,
                "credential_encryption_keys": {
                    "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
                },
                "provider_endpoints": {"dashscope": provider_url + "/v1"},
            }
        )
        try:
            asyncio.run(with_store(configured, prepare))
            requests = [
                PersistentChatRequest(
                    user_id=6,
                    session_id=session,
                    request_id=str(uuid4()),
                    client_message_id="qa-" + uuid4().hex,
                    content="QA",
                )
                for session in rows["sessions"]
            ]
            with running_server(create_app(configured)) as address:

                def post(payload):
                    path = "/internal/v1/chat/persistent"
                    body = payload.model_dump_json(by_alias=True, exclude_none=True).encode()
                    timestamp, nonce = str(int(time.time())), uuid4().hex
                    return httpx.post(
                        address + path,
                        content=body,
                        timeout=15,
                        headers={
                            "Content-Type": "application/json",
                            "X-Catgal-Timestamp": timestamp,
                            "X-Catgal-Nonce": nonce,
                            "X-Catgal-Signature": compute_signature(
                                TEST_SECRET, timestamp, nonce, "POST", path, body
                            ),
                        },
                    )

                with ThreadPoolExecutor(max_workers=2) as pool:
                    responses = list(pool.map(post, requests))
                assert all("event: done" in response.text for response in responses)
                assert {call[0] for call in calls} == {
                    "Bearer qa-alpha-key-1111",
                    "Bearer qa-beta-key-2222",
                }
                assert all(call[1].endswith("persona-v1") for call in calls)
                assert (
                    '"replayed":true'
                    in post(requests[0].model_copy(update={"request_id": str(uuid4())})).text
                )
                assert len(calls) == 2

                async def update(_store, bots):
                    await bots.rotate_credential(
                        CredentialRotate(
                            user_id=6,
                            credential_id=rows["credentials"][0],
                            revision=1,
                            api_key=SecretStr("qa-alpha-new-3333"),
                        )
                    )
                    await bots.update_bot(
                        BotUpdate(
                            user_id=6,
                            bot_id=rows["bots"][0],
                            revision=1,
                            name="QA temporary",
                            persona="persona-v2",
                            credential_id=rows["credentials"][0],
                            model="qa-model",
                        )
                    )

                asyncio.run(with_store(configured, update))
                later = requests[0].model_copy(
                    update={"request_id": str(uuid4()), "client_message_id": "qa-" + uuid4().hex}
                )
                assert "event: done" in post(later).text
                assert calls[-1][0] == "Bearer qa-alpha-new-3333" and calls[-1][1].endswith(
                    "persona-v2"
                )

                async def revoke_and_verify(store, bots):
                    await bots.revoke_credential(
                        CredentialRevoke(
                            user_id=6, credential_id=rows["credentials"][0], revision=2
                        )
                    )
                    async with store.sessions() as db:
                        snapshots = list(
                            (
                                await db.execute(
                                    select(Message.prompt_version_id, Message.credential_revision)
                                    .where(
                                        Message.session_id == rows["sessions"][0],
                                        Message.role == "assistant",
                                    )
                                    .order_by(Message.id)
                                )
                            ).all()
                        )
                        assert snapshots[0][0] != snapshots[1][0]
                        assert [row[1] for row in snapshots] == [1, 2]

                asyncio.run(with_store(configured, revoke_and_verify))
                assert (
                    '"replayed":true'
                    in post(requests[0].model_copy(update={"request_id": str(uuid4())})).text
                )
                rejected = post(
                    later.model_copy(
                        update={
                            "request_id": str(uuid4()),
                            "client_message_id": "qa-" + uuid4().hex,
                        }
                    )
                )
                assert "CREDENTIAL_UNAVAILABLE" in rejected.text and len(calls) == 3
        finally:
            asyncio.run(with_store(configured, clean))
