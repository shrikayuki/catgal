import asyncio
import base64
import os
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import delete, text

from app.bots.contracts import BotCreate, BotDelete, CredentialCreate
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.store import BotStore
from app.config import Settings
from app.long_memory.contracts import MemoryCreate, MemorySettingsUpdate, MemoryUpdate
from app.long_memory.models import LongTermMemory
from app.long_memory.store import LongTermMemoryStore
from app.memory_vector.contracts import MemoryVectorRequest, MemoryVectorUpdate
from app.memory_vector.models import MemoryVectorOutbox, MemoryVectorSettings
from app.memory_vector.store import MemoryVectorStore
from app.persistence.contracts import PersistenceError
from app.persistence.database import create_database, session_factory
from app.persistence.store import ChatStore

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_MEMORY_VECTOR_TEST") != "1", reason="Explicit live V16 MySQL opt-in"
)


async def assert_event_due(connection, memory_id):
    row = (
        await connection.execute(
            text(
                "SELECT TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),next_attempt_at),"
                "TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),dirty_since),"
                "TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),update_time) "
                "FROM ai_memory_vector_outbox WHERE memory_id=:id"
            ),
            {"id": memory_id},
        )
    ).first()
    assert row is not None and all(abs(value) < 2_000_000 for value in row)


async def timezone_regression(engine, bot_id):
    async with engine.connect() as connection:
        await connection.execute(text("SET time_zone='+08:00'"))
        await connection.commit()
        transaction = await connection.begin()
        try:
            result = await connection.execute(
                text(
                    "INSERT INTO ai_memory(user_id,bot_id,memory_type,content,revision,status,"
                    "create_time,update_time) "
                    "VALUES(6,:bot,'fact','QA_TIMEZONE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                ),
                {"bot": bot_id},
            )
            memory_id = result.lastrowid
            await assert_event_due(connection, memory_id)
            await connection.execute(
                text("UPDATE ai_memory SET content='QA_TIMEZONE_UPDATED',revision=2 WHERE id=:id"),
                {"id": memory_id},
            )
            await assert_event_due(connection, memory_id)
            # Force each INSERT SELECT path to create a fresh queue row under the +08 session.
            await connection.execute(
                text("DELETE FROM ai_memory_vector_outbox WHERE memory_id=:id"), {"id": memory_id}
            )
            await connection.execute(
                text(
                    "INSERT INTO ai_memory_vector_settings(bot_id,user_id,enabled,revision,"
                    "create_time,update_time) "
                    "VALUES(:bot,6,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                ),
                {"bot": bot_id},
            )
            await assert_event_due(connection, memory_id)
            await connection.execute(
                text("DELETE FROM ai_memory_vector_outbox WHERE memory_id=:id"), {"id": memory_id}
            )
            await connection.execute(
                text("UPDATE ai_memory_vector_settings SET enabled=0,revision=2 WHERE bot_id=:bot"),
                {"bot": bot_id},
            )
            await assert_event_due(connection, memory_id)
            await connection.execute(
                text("DELETE FROM ai_memory_vector_outbox WHERE memory_id=:id"), {"id": memory_id}
            )
            await connection.execute(
                text("UPDATE ai_bot SET memory_enabled=1 WHERE id=:bot"), {"bot": bot_id}
            )
            await assert_event_due(connection, memory_id)
        finally:
            await transaction.rollback()
            await connection.execute(text("SET time_zone='+00:00'"))
            await connection.commit()


@pytest.mark.asyncio
async def test_real_mysql_private_consent_current_versions_cas_and_timezone_triggers():
    settings = Settings(_env_file=None).model_copy(
        update={
            "app_env": "test",
            "persistence_enabled": True,
            "personalization_enabled": True,
            "long_term_memory_enabled": True,
            "memory_vector_enabled": True,
            "credential_encryption_keys": {
                "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
            },
        }
    )
    engine = create_database(settings).execution_options(isolation_level="REPEATABLE READ")
    sessions = session_factory(engine)
    memory = LongTermMemoryStore(sessions)
    chat = ChatStore(sessions, "qa-model", long_term_memory=memory)
    vectors = MemoryVectorStore(chat, settings)
    bots = BotStore(sessions, settings)
    created = {"bots": [], "credentials": []}
    prefix = "QA-private-" + uuid4().hex
    try:
        assert await vectors.health()
        credential = await bots.create_credential(
            CredentialCreate(
                user_id=6,
                provider_code="dashscope",
                label=prefix,
                api_key=SecretStr("qa-private-key-1234"),
            )
        )
        created["credentials"].append(int(credential["id"]))
        for _ in range(2):
            bot = await bots.create_bot(
                BotCreate(
                    user_id=6, name=prefix, credential_id=int(credential["id"]), model="qa-model"
                )
            )
            created["bots"].append(int(bot["id"]))
        bot_id, timezone_bot = created["bots"]
        await timezone_regression(engine, timezone_bot)
        owner = {"user_id": 6, "bot_id": bot_id}
        assert (await vectors.settings(MemoryVectorRequest(**owner)))["revision"] == "0"
        await memory.update_settings(MemorySettingsUpdate(**owner, revision=1, memory_enabled=True))
        saved = await memory.create(
            MemoryCreate(**owner, content="QA confirmed private", memory_type="fact")
        )
        memory_id = int(saved["id"])
        grants = await asyncio.gather(
            *[
                vectors.update_settings(MemoryVectorUpdate(**owner, revision=0, enabled=True))
                for _ in range(2)
            ],
            return_exceptions=True,
        )
        assert sum(isinstance(value, dict) for value in grants) == 1
        errors = [value for value in grants if isinstance(value, PersistenceError)]
        assert len(errors) == 1 and errors[0].code == "REVISION_CONFLICT"
        source = await vectors.source(memory_id)
        assert source.content == "fact\nQA confirmed private" and source.version >= 2
        candidate = source.document(settings.embedding_model)
        assert await vectors.hydrate(6, bot_id, 1, [candidate]) == [
            {"type": "fact", "content": "QA confirmed private"}
        ]
        assert await vectors.hydrate(6, bot_id, 1, [{**candidate, "user_id": 1000000}]) is None
        assert await vectors.hydrate(6, bot_id, 1, [{**candidate, "bot_id": timezone_bot}]) is None
        await memory.update(
            MemoryUpdate(
                **owner,
                memory_id=memory_id,
                revision=1,
                content="QA current private",
                memory_type="fact",
            )
        )
        assert not await vectors.acknowledge(source)
        assert await vectors.hydrate(6, bot_id, 1, [candidate]) is None
        current = await vectors.source(memory_id)
        assert current.version > source.version and current.source_revision == 2
        await vectors.failed(current, "EMBEDDING_UNAVAILABLE")
        async with sessions() as db:
            event = await db.get(MemoryVectorOutbox, memory_id)
            assert (
                event.pending
                and event.attempts == 1
                and event.last_error == "EMBEDDING_UNAVAILABLE"
            )
        await vectors.update_settings(MemoryVectorUpdate(**owner, revision=1, enabled=False))
        assert (await vectors.source(memory_id)).content is None
        assert (
            await vectors.hydrate(6, bot_id, 1, [current.document(settings.embedding_model)])
            is None
        )
        assert "QA current private" in await memory.recall(6, bot_id)
        await memory.update_settings(
            MemorySettingsUpdate(**owner, revision=2, memory_enabled=False)
        )
        assert await memory.recall(6, bot_id) == ""
        await bots.delete_bot(BotDelete(**owner, revision=3))
        assert not (await vectors.settings(MemoryVectorRequest(**owner)))["enabled"]
        assert (await vectors.source(memory_id)).content is None
    finally:
        async with sessions.begin() as db:
            await db.execute(
                delete(MemoryVectorSettings).where(MemoryVectorSettings.bot_id.in_(created["bots"]))
            )
            await db.execute(
                delete(LongTermMemory).where(LongTermMemory.bot_id.in_(created["bots"]))
            )
            # Source-delete triggers run first. Remove only these QA tombstones afterwards.
            await db.execute(
                delete(MemoryVectorOutbox).where(MemoryVectorOutbox.bot_id.in_(created["bots"]))
            )
            await db.execute(delete(PromptVersion).where(PromptVersion.bot_id.in_(created["bots"])))
            await db.execute(delete(Bot).where(Bot.id.in_(created["bots"])))
            await db.execute(delete(Credential).where(Credential.id.in_(created["credentials"])))
        await engine.dispose()
