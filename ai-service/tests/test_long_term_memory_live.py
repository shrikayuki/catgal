import asyncio
import base64
import os
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import delete, select
from test_persistence_service import Runtime, collect
from test_persistence_store import chat

from app.bots.contracts import BotCreate, BotDelete, CredentialCreate
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.runtime import RuntimeProvider
from app.bots.store import BotStore
from app.config import Settings
from app.long_memory.contracts import (
    MemoryCreate,
    MemoryDelete,
    MemoryQuery,
    MemorySettingsRequest,
    MemorySettingsUpdate,
    MemoryUpdate,
)
from app.long_memory.models import LongTermMemory
from app.long_memory.store import LongTermMemoryStore
from app.persistence.context import DurableContext
from app.persistence.contracts import PersistenceError, SessionCreate
from app.persistence.database import create_database, session_factory
from app.persistence.models import Conversation, Message
from app.persistence.service import PersistentChat
from app.persistence.store import ChatStore

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_MEMORY_TEST") != "1", reason="Explicit live V12 MySQL opt-in"
)


@pytest.mark.asyncio
async def test_real_mysql_memory_locks_sources_recall_and_cleanup():
    settings = Settings(_env_file=None).model_copy(
        update={
            "app_env": "test",
            "persistence_enabled": True,
            "personalization_enabled": True,
            "long_term_memory_enabled": True,
            "redis_url": None,
            "credential_encryption_keys": {
                "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
            },
        }
    )
    engine = create_database(settings)
    sessions = session_factory(engine)
    bots = BotStore(sessions, settings)
    memories = LongTermMemoryStore(sessions)
    store = ChatStore(sessions, "qa-model", bots=bots, long_term_memory=memories)
    created = {"bots": [], "credentials": [], "sessions": []}
    try:
        assert await memories.health()
        for _ in range(2):
            credential = await bots.create_credential(
                CredentialCreate(
                    user_id=6,
                    provider_code="dashscope",
                    label="QA-memory-" + uuid4().hex,
                    api_key=SecretStr("qa-memory-key-1234"),
                )
            )
            created["credentials"].append(int(credential["id"]))
            bot = await bots.create_bot(
                BotCreate(
                    user_id=6,
                    name="QA-memory-" + uuid4().hex,
                    credential_id=int(credential["id"]),
                    model="qa-model",
                )
            )
            created["bots"].append(int(bot["id"]))
            session = await store.create_session(SessionCreate(user_id=6, bot_id=int(bot["id"])))
            created["sessions"].append(session["sessionId"])
        bot_id, other_id = created["bots"]
        owner = {"user_id": 6, "bot_id": bot_id}
        defaults = await memories.settings(MemorySettingsRequest(**owner))
        assert defaults["memoryEnabled"] is False
        enabled = await memories.update_settings(
            MemorySettingsUpdate(**owner, revision=1, memory_enabled=True)
        )
        assert enabled["revision"] == "2"
        source = await store.begin(chat(created["sessions"][0], user=6))
        await store.finish(source, "QA completed source")
        item = await memories.create(
            MemoryCreate(
                **owner,
                content="QA_MEMORY_ORIGINAL",
                memory_type="preference",
                source_message_id=source.assistant.id,
            )
        )
        with pytest.raises(PersistenceError, match="INVALID_MEMORY_SOURCE"):
            await memories.create(
                MemoryCreate(
                    user_id=6,
                    bot_id=other_id,
                    content="invalid source",
                    memory_type="fact",
                    source_message_id=source.assistant.id,
                )
            )
        with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
            await memories.list(MemoryQuery(user_id=bot_id + 1000000, bot_id=bot_id))

        async def update(content):
            return await memories.update(
                MemoryUpdate(
                    **owner,
                    memory_id=int(item["id"]),
                    revision=1,
                    content=content,
                    memory_type="fact",
                )
            )

        outcomes = await asyncio.gather(
            update("QA_MEMORY_UPDATED_A"), update("QA_MEMORY_UPDATED_B"), return_exceptions=True
        )
        assert sum(isinstance(result, dict) for result in outcomes) == 1
        conflicts = [result for result in outcomes if isinstance(result, PersistenceError)]
        assert len(conflicts) == 1 and conflicts[0].code == "REVISION_CONFLICT"
        final = (await memories.list(MemoryQuery(**owner)))["items"][0]
        assert final["revision"] == "2" and final["sourceMessageId"] == str(source.assistant.id)
        runtime = Runtime()
        provider = RuntimeProvider(settings, lambda _settings, _memory: runtime)
        service = PersistentChat(store, DurableContext(store), provider, 5)
        result = await collect(service, chat(created["sessions"][0], "with-memory", user=6))
        assert result[-1][0] == "done" and final["content"] in runtime.calls[-1][2]
        await memories.delete(MemoryDelete(**owner, memory_id=int(item["id"]), revision=2))
        await collect(service, chat(created["sessions"][0], "after-forget", user=6))
        assert "QA_MEMORY_UPDATED_" not in runtime.calls[-1][2]
        async with sessions() as db:
            deleted = await db.get(LongTermMemory, int(item["id"]))
            assert deleted.content == "" and deleted.source_message_id is None
        await bots.delete_bot(BotDelete(**owner, revision=2))
        assert (await memories.settings(MemorySettingsRequest(**owner)))["memoryEnabled"]
        await memories.update_settings(
            MemorySettingsUpdate(**owner, revision=3, memory_enabled=False)
        )
    finally:
        # Remove only the random QA objects created by this test, in foreign-key order.
        async with sessions.begin() as db:
            await db.execute(
                delete(LongTermMemory).where(LongTermMemory.bot_id.in_(created["bots"]))
            )
            message_ids = await db.scalars(
                select(Message.id)
                .where(Message.session_id.in_(created["sessions"]))
                .order_by(Message.id.desc())
            )
            for message_id in message_ids:
                await db.execute(delete(Message).where(Message.id == message_id))
            await db.execute(
                delete(Conversation).where(Conversation.session_id.in_(created["sessions"]))
            )
            await db.execute(delete(PromptVersion).where(PromptVersion.bot_id.in_(created["bots"])))
            await db.execute(delete(Bot).where(Bot.id.in_(created["bots"])))
            await db.execute(delete(Credential).where(Credential.id.in_(created["credentials"])))
        await engine.dispose()
