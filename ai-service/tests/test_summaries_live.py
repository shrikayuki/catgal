import asyncio
import base64
import os
from dataclasses import replace
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import delete, select, text, update
from sqlalchemy.exc import DBAPIError
from test_persistence_store import chat

from app.bots.contracts import BotCreate, CredentialCreate
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.store import BotStore
from app.config import Settings
from app.persistence.contracts import PersistenceError, SessionCreate, SessionRequest
from app.persistence.database import create_database, session_factory
from app.persistence.models import Conversation, Message
from app.persistence.store import ChatStore
from app.summaries.contracts import SummaryRequest, SummarySettings
from app.summaries.models import SessionSummary
from app.summaries.store import SummaryStore

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_SUMMARY_TEST") != "1", reason="Explicit live V13 MySQL opt-in"
)


@pytest.mark.asyncio
async def test_real_mysql_summary_schema_locks_watermarks_and_flag_off_delete_trigger():
    settings = Settings(_env_file=None).model_copy(
        update={
            "app_env": "test",
            "persistence_enabled": True,
            "personalization_enabled": True,
            "session_summary_enabled": True,
            "redis_url": None,
            "credential_encryption_keys": {
                "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
            },
        }
    )
    engine = create_database(settings)
    sessions = session_factory(engine)
    bots = BotStore(sessions, settings)
    store = ChatStore(sessions, "qa-model", bots=bots)
    summaries = SummaryStore(store, settings)
    store.summaries = summaries
    created = {"bots": [], "credentials": [], "sessions": []}
    request_prefix = "qa-summary-" + uuid4().hex
    try:
        assert await summaries.health()
        async with sessions() as db:
            column = (
                await db.execute(
                    text(
                        "SELECT character_set_name,collation_name FROM information_schema.columns "
                        "WHERE table_schema=DATABASE() AND table_name='ai_session_summary' "
                        "AND column_name='session_id'"
                    )
                )
            ).first()
            assert tuple(column) == ("ascii", "ascii_bin")
            trigger = await db.scalar(
                text(
                    "SELECT COUNT(*) FROM information_schema.triggers "
                    "WHERE trigger_schema=DATABASE() "
                    "AND trigger_name='trg_ai_summary_session_deleted_v13' "
                    "AND action_timing='AFTER' AND event_manipulation='UPDATE'"
                )
            )
            assert trigger == 1
        credential = await bots.create_credential(
            CredentialCreate(
                user_id=6,
                provider_code="dashscope",
                label="QA-summary-" + uuid4().hex,
                api_key=SecretStr("qa-summary-key-1234"),
            )
        )
        created["credentials"].append(int(credential["id"]))
        bot = await bots.create_bot(
            BotCreate(
                user_id=6,
                name="QA-summary-" + uuid4().hex,
                credential_id=int(credential["id"]),
                model="qa-model",
            )
        )
        created["bots"].append(int(bot["id"]))
        for _ in range(2):
            session = await store.create_session(SessionCreate(user_id=6, bot_id=int(bot["id"])))
            created["sessions"].append(session["sessionId"])
        session_id, untouched = created["sessions"]
        owner = {"user_id": 6, "session_id": session_id}
        assert (await summaries.get(SummaryRequest(**owner)))["revision"] == "0"

        async def enable():
            return await summaries.settings(SummarySettings(**owner, revision=0, enabled=True))

        results = await asyncio.gather(enable(), enable(), return_exceptions=True)
        assert sum(isinstance(result, dict) for result in results) == 1
        errors = [result for result in results if isinstance(result, PersistenceError)]
        assert len(errors) == 1 and errors[0].code == "REVISION_CONFLICT"
        assert not (await summaries.get(SummaryRequest(user_id=6, session_id=untouched)))["enabled"]
        with pytest.raises(PersistenceError, match="SESSION_NOT_FOUND"):
            await summaries.get(SummaryRequest(user_id=1000000, session_id=session_id))
        sources = []
        for index in range(11):
            turn = await store.begin(chat(session_id, f"{request_prefix}-{index}", user=6))
            await store.finish(turn, f"QA old answer {index}")
            sources.append(turn)
        active = await store.begin(chat(session_id, request_prefix + "-current", user=6))
        _, plan = await summaries.prepare(active)
        assert plan.user_message_id == sources[0].user_message.id
        assert plan.assistant_message_id == sources[0].assistant.id
        assert not await summaries.commit(
            active, replace(plan, source_memory_version=plan.source_memory_version - 1), "stale"
        )
        assert await summaries.commit(active, plan, "QA summary")
        assert not await summaries.commit(active, plan, "stale revision")
        state = await summaries.get(SummaryRequest(**owner))
        assert state["coveredTurns"] == 1 and state["sourceMemoryVersion"] == "11"
        assert state["coveredThroughMessageId"] == str(sources[0].user_message.id)

        for changes, error_number in (
            ({"summary": "可" * 3001}, 3819),
            ({"covered_user_message_id": -1}, 1452),
            ({"summary": "", "covered_turns": 1}, 3819),
        ):
            with pytest.raises(DBAPIError) as rejected:
                async with sessions.begin() as db:
                    await db.execute(
                        update(SessionSummary)
                        .where(SessionSummary.session_id == session_id)
                        .values(**changes)
                    )
            assert rejected.value.orig.args[0] == error_number

        # Exercise the database trigger through the normal delete entry with the feature OFF.
        store.summaries = None
        await store.delete_session(SessionRequest(**owner))
        async with sessions() as db:
            row = await db.get(SessionSummary, session_id)
            assert not row.enabled and row.summary == "" and row.status == "empty"
            assert row.covered_user_message_id is row.covered_assistant_message_id is None
            assert row.source_memory_version is row.last_error_code is None
            assert row.covered_turns == 0 and row.revision == int(state["revision"]) + 1
        assert not await summaries.commit(active, plan, "late answer after delete")
    finally:
        # Only the random QA records created above are removed, in dependency order.
        async with sessions.begin() as db:
            await db.execute(
                delete(SessionSummary).where(SessionSummary.session_id.in_(created["sessions"]))
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
