import asyncio
import base64
import os
from uuid import uuid4

import pytest
from pydantic import SecretStr
from sqlalchemy import delete, func, select, text, update
from sqlalchemy.exc import DBAPIError
from test_persistence_store import chat

from app.bots.contracts import BotCreate, CredentialCreate
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.store import BotStore
from app.candidates.contracts import (
    CandidateConfirm,
    CandidateQuery,
    CandidateSettingsUpdate,
    ExtractedItem,
)
from app.candidates.models import Candidate, Extraction, SuggestionSettings
from app.candidates.store import CandidateStore
from app.config import Settings
from app.long_memory.models import LongTermMemory
from app.long_memory.store import LongTermMemoryStore
from app.persistence.contracts import PersistenceError, SessionCreate, SessionRequest
from app.persistence.database import create_database, session_factory
from app.persistence.models import Conversation, Message
from app.persistence.store import ChatStore

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_CANDIDATES_TEST") != "1", reason="Explicit live V14 MySQL opt-in"
)


@pytest.mark.asyncio
async def test_real_mysql_candidate_rr_confirmation_dedup_and_flag_off_erasure_trigger():
    settings = Settings(_env_file=None).model_copy(
        update={
            "app_env": "test",
            "persistence_enabled": True,
            "personalization_enabled": True,
            "long_term_memory_enabled": True,
            "memory_candidates_enabled": True,
            "redis_url": None,
            "credential_encryption_keys": {
                "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
            },
        }
    )
    # Verify current-read correctness even if an operator chooses RR instead of the normal RC.
    engine = create_database(settings).execution_options(isolation_level="REPEATABLE READ")
    sessions = session_factory(engine)
    bots = BotStore(sessions, settings)
    store = ChatStore(
        sessions, "qa-model", bots=bots, long_term_memory=LongTermMemoryStore(sessions)
    )
    candidates = CandidateStore(store, settings)
    store.candidates = candidates
    created = {"bots": [], "credentials": [], "sessions": []}
    prefix = "qa-candidate-" + uuid4().hex
    try:
        assert await candidates.health()
        async with sessions() as db:
            trigger = await db.scalar(
                text(
                    "SELECT COUNT(*) FROM information_schema.triggers "
                    "WHERE trigger_schema=DATABASE() "
                    "AND trigger_name='trg_ai_candidates_session_deleted_v14' "
                    "AND action_timing='AFTER' AND event_manipulation='UPDATE'"
                )
            )
            assert trigger == 1
            collations = (
                await db.execute(
                    text(
                        "SELECT character_set_name,collation_name FROM information_schema.columns "
                        "WHERE table_schema=DATABASE() AND ((table_name='ai_memory_extraction' "
                        "AND column_name='session_id') OR (table_name='ai_memory_candidate' "
                        "AND column_name='source_session_id'))"
                    )
                )
            ).all()
            assert len(collations) == 2 and all(
                tuple(row) == ("ascii", "ascii_bin") for row in collations
            )
        credential = await bots.create_credential(
            CredentialCreate(
                user_id=6,
                provider_code="dashscope",
                label=prefix,
                api_key=SecretStr("qa-candidate-key-1234"),
            )
        )
        created["credentials"].append(int(credential["id"]))
        bot = await bots.create_bot(
            BotCreate(user_id=6, name=prefix, credential_id=int(credential["id"]), model="qa-model")
        )
        bot_id = int(bot["id"])
        created["bots"].append(bot_id)
        owner = {"user_id": 6, "bot_id": bot_id}
        for _ in range(3):
            session = await store.create_session(SessionCreate(**owner))
            created["sessions"].append(session["sessionId"])
        updates = await asyncio.gather(
            *[
                candidates.update_settings(
                    CandidateSettingsUpdate(**owner, revision=0, enabled=True)
                )
                for _ in range(2)
            ],
            return_exceptions=True,
        )
        assert sum(isinstance(item, dict) for item in updates) == 1
        errors = [item for item in updates if isinstance(item, PersistenceError)]
        assert len(errors) == 1 and errors[0].code == "REVISION_CONFLICT"
        source_ids = []
        for index, session_id in enumerate(created["sessions"]):
            prepared = await store.begin(chat(session_id, prefix + str(index), user=6))
            plan = await candidates.start(prepared)
            assert plan is not None
            assert await candidates.finish(
                prepared, plan, [ExtractedItem(content="QA preference", memory_type="preference")]
            )
            await store.finish(prepared, "QA reply")
            source_ids.append(prepared.user_message.id)
        async with sessions() as db:
            assert (
                await db.scalar(
                    select(func.count())
                    .select_from(LongTermMemory)
                    .where(LongTermMemory.bot_id == bot_id)
                )
                == 0
            )
        page = await candidates.list(CandidateQuery(**owner))
        by_source = {int(item["sourceMessageId"]): item for item in page["items"]}
        # Distinct sessions hold distinct Conversation locks and compete for the same Bot lock.
        payloads = [
            CandidateConfirm(
                **owner,
                candidate_id=int(by_source[source_ids[index]]["id"]),
                revision=1,
                content="QA preference" if index == 0 else "ｑａ　PREFERENCE",
                memory_type="preference",
            )
            for index in range(2)
        ]
        confirmed = await asyncio.gather(*(candidates.confirm(payload) for payload in payloads))
        assert confirmed[0]["memoryId"] == confirmed[1]["memoryId"]
        assert sorted(item["duplicate"] for item in confirmed) == [False, True]
        async with sessions() as db:
            assert (
                await db.scalar(
                    select(func.count())
                    .select_from(LongTermMemory)
                    .where(LongTermMemory.bot_id == bot_id, LongTermMemory.status == 1)
                )
                == 1
            )
            processed = list(
                await db.scalars(
                    select(Candidate).where(Candidate.source_message_id.in_(source_ids[:2]))
                )
            )
            assert all(row.content == "" and row.state == "confirmed" for row in processed)
        pending_id = int(by_source[source_ids[2]]["id"])
        with pytest.raises(DBAPIError) as rejected:
            async with sessions.begin() as db:
                await db.execute(
                    update(Candidate).where(Candidate.id == pending_id).values(content="x" * 1001)
                )
        assert rejected.value.orig.args[0] == 3819
        # Start a second extraction in the third session, then exercise both trigger branches.
        active = await store.begin(chat(created["sessions"][2], prefix + "-running", user=6))
        plan = await candidates.start(active)
        store.candidates = None
        await store.delete_session(SessionRequest(user_id=6, session_id=created["sessions"][2]))
        async with sessions() as db:
            erased = await db.get(Candidate, pending_id)
            running = await db.get(Extraction, active.user_message.id)
            assert erased.state == "invalidated" and erased.content == ""
            assert running.state == "invalidated" and running.error_code == "SOURCE_NOT_AVAILABLE"
        assert not await candidates.finish(
            active, plan, [ExtractedItem(content="late response", memory_type="fact")]
        )
        with pytest.raises(PersistenceError, match="SOURCE_NOT_AVAILABLE"):
            await candidates.confirm(
                CandidateConfirm(
                    **owner,
                    candidate_id=pending_id,
                    revision=1,
                    content="old source",
                    memory_type="fact",
                )
            )
    finally:
        # Only randomized QA-owned objects are removed, in full FK dependency order.
        async with sessions.begin() as db:
            await db.execute(delete(Candidate).where(Candidate.bot_id.in_(created["bots"])))
            await db.execute(delete(Extraction).where(Extraction.bot_id.in_(created["bots"])))
            await db.execute(
                delete(SuggestionSettings).where(SuggestionSettings.bot_id.in_(created["bots"]))
            )
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
