import asyncio
import json
from datetime import timedelta

import httpx
import pytest
from sqlalchemy import func, select, text
from test_bots import bot_settings as bot_settings
from test_bots import bots as bots
from test_bots import custom
from test_persistence_service import collect, post
from test_persistence_store import chat
from test_persistence_store import store as store

from app.bots.contracts import BotDelete
from app.bots.runtime import RuntimeProvider
from app.candidates.content import contains_credential
from app.candidates.contracts import (
    CandidateConfirm,
    CandidateQuery,
    CandidateRequest,
    CandidateSettingsRequest,
    CandidateSettingsUpdate,
    ExtractedItem,
)
from app.candidates.models import Candidate, Extraction
from app.candidates.runtime import EXTRACTION_PROMPT, extract_candidates
from app.candidates.store import CandidateStore
from app.long_memory.contracts import MemoryCreate, MemoryDelete
from app.long_memory.models import LongTermMemory
from app.long_memory.store import LongTermMemoryStore
from app.main import create_app
from app.persistence.context import DurableContext
from app.persistence.contracts import PersistenceError, SessionCreate, SessionRequest
from app.persistence.models import Conversation, Message
from app.persistence.service import PersistentChat

FACT = {"content": "喜欢治愈系剧情", "memoryType": "preference"}


class ExtractionRuntime:
    def __init__(self, *, result=None, raw=None, failure=None, slow=False):
        self.result = {"items": [FACT]} if result is None else result
        self.raw = raw
        self.failure = failure
        self.slow = slow
        self.extraction_calls = []
        self.chat_calls = []
        self.started = asyncio.Event()
        self.closed = False

    async def generate(self, history, message, prompt):
        if prompt == EXTRACTION_PROMPT:
            self.extraction_calls.append((history, message, prompt))
            self.started.set()
            try:
                if self.slow:
                    await asyncio.sleep(30)
                if self.failure:
                    raise self.failure
                yield (
                    self.raw
                    if self.raw is not None
                    else json.dumps(self.result, ensure_ascii=False)
                )
            finally:
                self.closed = True
        else:
            self.chat_calls.append((history, message, prompt))
            yield "正常回答"


@pytest.fixture
def candidates(store, bots, bot_settings):
    config = bot_settings.model_copy(
        update={"long_term_memory_enabled": True, "memory_candidates_enabled": True}
    )
    store.long_term_memory = LongTermMemoryStore(store.sessions)
    store.candidates = CandidateStore(store, config)
    return store.candidates


async def setup(store, bots, candidates, *, enabled=True):
    bot = await custom(bots)
    session = await store.create_session(SessionCreate(user_id=7, bot_id=int(bot["id"])))
    if enabled:
        await candidates.update_settings(
            CandidateSettingsUpdate(user_id=7, bot_id=int(bot["id"]), revision=0, enabled=True)
        )
    return int(bot["id"]), session["sessionId"]


def owner(bot_id, **values):
    return {"user_id": 7, "bot_id": bot_id, **values}


def deadline():
    return asyncio.get_running_loop().time() + 30


async def suggest(
    store, candidates, bot_id, session_id, *, key="first", runtime=None, content="喜欢治愈剧情"
):
    runtime = runtime or ExtractionRuntime()
    prepared = await store.begin(chat(session_id, key, content))
    await extract_candidates(candidates, prepared, runtime, deadline())
    await store.finish(prepared, "回答")
    page = await candidates.list(CandidateQuery(**owner(bot_id)))
    return page, prepared, runtime


@pytest.mark.asyncio
async def test_candidate_settings_default_off_and_revision_zero_conflicts(store, bots, candidates):
    bot, session = await setup(store, bots, candidates, enabled=False)
    assert await candidates.settings(CandidateSettingsRequest(**owner(bot))) == {
        "botId": str(bot),
        "enabled": False,
        "revision": "0",
    }
    unchanged = await candidates.update_settings(
        CandidateSettingsUpdate(**owner(bot, revision=0, enabled=False))
    )
    assert unchanged["revision"] == "0"
    page, _, runtime = await suggest(store, candidates, bot, session)
    assert page["items"] == [] and page["lastExtraction"] is None
    assert runtime.extraction_calls == []
    enabled = await candidates.update_settings(
        CandidateSettingsUpdate(**owner(bot, revision=0, enabled=True))
    )
    assert enabled["revision"] == "1"
    with pytest.raises(PersistenceError, match="REVISION_CONFLICT"):
        await candidates.update_settings(
            CandidateSettingsUpdate(**owner(bot, revision=0, enabled=False))
        )


@pytest.mark.asyncio
async def test_enable_only_processes_new_messages_and_never_autosaves_memory(
    store, bots, candidates
):
    bot, session = await setup(store, bots, candidates, enabled=False)
    before = await store.begin(chat(session, "before-enable"))
    await candidates.update_settings(
        CandidateSettingsUpdate(**owner(bot, revision=0, enabled=True))
    )
    runtime = ExtractionRuntime()
    await extract_candidates(candidates, before, runtime, deadline())
    assert runtime.extraction_calls == []
    await store.finish(before, "old response")
    page, _, runtime = await suggest(store, candidates, bot, session, key="after-enable")
    assert len(page["items"]) == len(runtime.extraction_calls) == 1
    assert page["lastExtraction"]["status"] == "completed"
    async with store.sessions() as db:
        assert await db.scalar(select(func.count()).select_from(LongTermMemory)) == 0
    assert not (await store.long_term_memory.settings(CandidateSettingsRequest(**owner(bot))))[
        "memoryEnabled"
    ]


@pytest.mark.asyncio
async def test_confirm_is_atomic_uses_edited_content_and_erases_candidate(store, bots, candidates):
    bot, session = await setup(store, bots, candidates)
    page, prepared, _ = await suggest(store, candidates, bot, session)
    item = page["items"][0]
    assert item["sourceMessageId"] == str(prepared.user_message.id)
    assert item["sourceSessionId"] == session
    confirmed = await candidates.confirm(
        CandidateConfirm(
            **owner(
                bot,
                candidate_id=int(item["id"]),
                revision=1,
                content="偏好角色塑造",
                memory_type="fact",
            )
        )
    )
    assert not confirmed["duplicate"] and isinstance(confirmed["memoryId"], str)
    async with store.sessions() as db:
        memory = await db.get(LongTermMemory, int(confirmed["memoryId"]))
        candidate = await db.get(Candidate, int(item["id"]))
        assert memory.content == "偏好角色塑造" and memory.memory_type == "fact"
        assert memory.source_message_id == prepared.user_message.id
        assert candidate.content == "" and candidate.state == "confirmed"
        assert candidate.confirmed_memory_id == memory.id
    assert (await candidates.list(CandidateQuery(**owner(bot))))["items"] == []
    with pytest.raises(PersistenceError, match="REVISION_CONFLICT"):
        await candidates.confirm(
            CandidateConfirm(
                **owner(
                    bot,
                    candidate_id=int(item["id"]),
                    revision=1,
                    content="再次保存",
                    memory_type="fact",
                )
            )
        )


@pytest.mark.asyncio
async def test_confirmation_deduplicates_existing_memory_and_deleted_memory_is_not_reused(
    store, bots, candidates
):
    bot, session = await setup(store, bots, candidates)
    existing = await store.long_term_memory.create(
        MemoryCreate(**owner(bot, content="QA GAME", memory_type="preference"))
    )
    page, _, _ = await suggest(store, candidates, bot, session)
    first = page["items"][0]
    result = await candidates.confirm(
        CandidateConfirm(
            **owner(
                bot,
                candidate_id=int(first["id"]),
                revision=1,
                content="ｑａ　game",
                memory_type="preference",
            )
        )
    )
    assert result == {"memoryId": existing["id"], "duplicate": True}
    await store.long_term_memory.delete(
        MemoryDelete(**owner(bot, memory_id=int(existing["id"]), revision=1))
    )
    page, _, _ = await suggest(store, candidates, bot, session, key="second")
    second = page["items"][0]
    result = await candidates.confirm(
        CandidateConfirm(
            **owner(
                bot,
                candidate_id=int(second["id"]),
                revision=1,
                content="QA GAME",
                memory_type="preference",
            )
        )
    )
    assert result["memoryId"] != existing["id"] and not result["duplicate"]


@pytest.mark.asyncio
async def test_confirmation_rolls_back_memory_if_candidate_update_fails(
    store, bots, candidates, monkeypatch
):
    bot, session = await setup(store, bots, candidates)
    page, _, _ = await suggest(store, candidates, bot, session)
    item = page["items"][0]
    original = candidates._erase

    def fail(*args):
        original(*args)
        raise RuntimeError("QA atomic rollback")

    monkeypatch.setattr(candidates, "_erase", fail)
    with pytest.raises(RuntimeError, match="QA atomic rollback"):
        await candidates.confirm(
            CandidateConfirm(
                **owner(
                    bot,
                    candidate_id=int(item["id"]),
                    revision=1,
                    content=FACT["content"],
                    memory_type="preference",
                )
            )
        )
    async with store.sessions() as db:
        assert await db.scalar(select(func.count()).select_from(LongTermMemory)) == 0
        row = await db.get(Candidate, int(item["id"]))
        assert row.state == "pending" and row.content == FACT["content"]


@pytest.mark.asyncio
async def test_private_bot_and_candidate_permissions_and_stable_pagination(store, bots, candidates):
    bot, session = await setup(store, bots, candidates)
    other, _ = await setup(store, bots, candidates)
    for index in range(3):
        await suggest(store, candidates, bot, session, key=f"message-{index}")
    page = await candidates.list(CandidateQuery(**owner(bot, limit=2)))
    assert len(page["items"]) == 2 and page["hasMore"]
    older = await candidates.list(CandidateQuery(**owner(bot, before_id=int(page["nextCursor"]))))
    assert len(older["items"]) == 1 and not older["hasMore"]
    item = page["items"][0]
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await candidates.list(CandidateQuery(**owner(bot, user_id=8)))
    with pytest.raises(PersistenceError, match="CANDIDATE_NOT_FOUND"):
        await candidates.reject(
            CandidateRequest(**owner(other, candidate_id=int(item["id"]), revision=1))
        )
    with pytest.raises(PersistenceError, match="CANDIDATE_NOT_FOUND"):
        await candidates.confirm(
            CandidateConfirm(
                **owner(
                    bot,
                    user_id=8,
                    candidate_id=int(item["id"]),
                    revision=1,
                    content="x",
                    memory_type="fact",
                )
            )
        )


@pytest.mark.asyncio
async def test_archived_bot_allows_read_disable_and_reject_but_not_confirm(store, bots, candidates):
    bot, session = await setup(store, bots, candidates)
    page, _, _ = await suggest(store, candidates, bot, session)
    item = page["items"][0]
    await bots.delete_bot(BotDelete(**owner(bot, revision=1)))
    assert (await candidates.settings(CandidateSettingsRequest(**owner(bot))))["enabled"]
    assert (await candidates.list(CandidateQuery(**owner(bot))))["items"]
    await candidates.update_settings(
        CandidateSettingsUpdate(**owner(bot, revision=1, enabled=False))
    )
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await candidates.update_settings(
            CandidateSettingsUpdate(**owner(bot, revision=2, enabled=True))
        )
    assert await candidates.reject(
        CandidateRequest(**owner(bot, candidate_id=int(item["id"]), revision=1))
    ) == {"rejected": True}
    async with store.sessions() as db:
        row = await db.get(Candidate, int(item["id"]))
        assert row.state == "rejected" and row.content == ""


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["disable", "delete", "source", "token", "lease"])
async def test_old_extraction_cannot_commit_after_source_settings_or_lease_changes(
    store, bots, candidates, action
):
    bot, session = await setup(store, bots, candidates)
    prepared = await store.begin(chat(session, "current"))
    plan = await candidates.start(prepared)
    if action == "disable":
        await candidates.update_settings(
            CandidateSettingsUpdate(**owner(bot, revision=1, enabled=False))
        )
    elif action == "delete":
        await store.delete_session(SessionRequest(user_id=7, session_id=session))
    else:
        async with store.sessions.begin() as db:
            if action == "source":
                row = await db.get(Message, prepared.user_message.id)
                row.content = "changed source"
            elif action == "token":
                row = await db.get(Message, prepared.assistant.id)
                row.generation_token = "different-generation-token"
            else:
                row = await db.scalar(
                    select(Conversation).where(Conversation.session_id == session)
                )
                row.active_lease_until -= timedelta(days=1)
    assert not await candidates.finish(prepared, plan, [ExtractedItem.model_validate(FACT)])
    async with store.sessions() as db:
        assert await db.scalar(select(func.count()).select_from(Candidate)) == 0
        job = await db.get(Extraction, prepared.user_message.id)
        assert job.state == "invalidated"


@pytest.mark.asyncio
async def test_confirmation_revalidates_deleted_or_changed_source_and_erases_candidate(
    store, bots, candidates
):
    bot, session = await setup(store, bots, candidates)
    page, prepared, _ = await suggest(store, candidates, bot, session)
    item = page["items"][0]
    async with store.sessions.begin() as db:
        row = await db.get(Message, prepared.user_message.id)
        row.content = "changed source"
    with pytest.raises(PersistenceError, match="SOURCE_NOT_AVAILABLE"):
        await candidates.confirm(
            CandidateConfirm(
                **owner(
                    bot, candidate_id=int(item["id"]), revision=1, content="x", memory_type="fact"
                )
            )
        )
    async with store.sessions() as db:
        row = await db.get(Candidate, int(item["id"]))
        assert row.content == "" and row.state == "invalidated"


@pytest.mark.asyncio
async def test_replay_and_regeneration_never_extract_same_user_message_again(
    store, bots, candidates
):
    bot, session = await setup(store, bots, candidates)
    runtime = ExtractionRuntime()
    provider = RuntimeProvider(candidates.config, lambda _settings, _memory: runtime)
    service = PersistentChat(store, DurableContext(store), provider, 20)
    current = chat(session, "current")
    first = await collect(service, current)
    assert first[-1][0] == "done"
    assert (await collect(service, current))[0][1]["replayed"]
    regenerated = chat(session, "regen", content=None, regenerate=int(first[0][1]["messageId"]))
    assert (await collect(service, regenerated))[-1][0] == "done"
    assert len(runtime.extraction_calls) == 1 and len(runtime.chat_calls) == 2
    assert len((await candidates.list(CandidateQuery(**owner(bot))))["items"]) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["provider", "json", "extra", "count", "size", "timeout", "empty"])
async def test_extraction_failure_or_empty_result_never_blocks_chat_or_leaks_body(
    store, bots, candidates, kind, caplog
):
    _, session = await setup(store, bots, candidates)
    raw = {
        "json": "PRIVATE_INVALID_JSON",
        "extra": '{"items":[],"bad":"PRIVATE_DETAIL"}',
        "size": "x" * 6001,
    }.get(kind)
    result = (
        {"items": []} if kind == "empty" else {"items": [FACT] * 4} if kind == "count" else None
    )
    runtime = ExtractionRuntime(
        raw=raw,
        result=result,
        slow=kind == "timeout",
        failure=RuntimeError("PRIVATE_DETAIL") if kind == "provider" else None,
    )
    candidates.config = candidates.config.model_copy(
        update={"candidate_extraction_timeout_seconds": 0.5 if kind == "timeout" else 5}
    )
    provider = RuntimeProvider(candidates.config, lambda _settings, _memory: runtime)
    events = await collect(
        PersistentChat(store, DurableContext(store), provider, 20), chat(session, "current")
    )
    assert events[-1][0] == "done" and len(runtime.chat_calls) == 1
    assert runtime.closed and "PRIVATE_" not in caplog.text
    async with store.sessions() as db:
        job = await db.scalar(select(Extraction))
        assert job.state == ("empty" if kind == "empty" else "failed")
        assert await db.scalar(select(func.count()).select_from(Candidate)) == 0


@pytest.mark.asyncio
async def test_sensitive_source_and_sensitive_or_duplicate_results_are_filtered(
    store, bots, candidates
):
    bot, session = await setup(store, bots, candidates)
    page, _, runtime = await suggest(
        store, candidates, bot, session, content="API Key: sk-sensitiveSecret123456789"
    )
    assert runtime.extraction_calls == [] and page["items"] == []
    assert page["lastExtraction"]["status"] == "skipped"
    result = {"items": [FACT, FACT, {"content": "password: secret123", "memoryType": "fact"}]}
    page, _, _ = await suggest(
        store, candidates, bot, session, key="safe", runtime=ExtractionRuntime(result=result)
    )
    assert len(page["items"]) == 1 and page["items"][0]["content"] == FACT["content"]


@pytest.mark.asyncio
async def test_cancel_interrupts_job_and_never_calls_main_model(store, bots, candidates):
    _, session = await setup(store, bots, candidates)
    runtime = ExtractionRuntime(slow=True)
    provider = RuntimeProvider(candidates.config, lambda _settings, _memory: runtime)
    task = asyncio.create_task(
        collect(
            PersistentChat(store, DurableContext(store), provider, 20), chat(session, "current")
        )
    )
    await asyncio.wait_for(runtime.started.wait(), 2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert runtime.closed and runtime.chat_calls == []
    async with store.sessions() as db:
        job = await db.scalar(select(Extraction))
        assert job.state == "interrupted" and job.error_code == "EXTRACTION_INTERRUPTED"
        assert await db.scalar(select(func.count()).select_from(Candidate)) == 0


@pytest.mark.asyncio
async def test_v13_compatibility_with_candidates_disabled_and_hmac_api_validation(
    store, bots, bot_settings, candidates
):
    bot, session = await setup(store, bots, candidates, enabled=False)
    app = create_app(
        candidates.config,
        runtime_factory=lambda _settings, _memory: ExtractionRuntime(),
        store=store,
    )
    body = {"userId": 7, "botId": str(bot)}
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            path = "/internal/v1/memory-candidates/settings/get"
            assert (await post(client, path, body, signed=False)).status_code == 401
            assert (await post(client, path, body)).json()["revision"] == "0"
            invalid = await post(
                client,
                "/internal/v1/memory-candidates/confirm",
                {
                    **body,
                    "candidateId": "1",
                    "revision": 1,
                    "content": "PRIVATE_INPUT",
                    "memoryType": "fact",
                    "status": 1,
                },
            )
            assert invalid.status_code == 422 and "PRIVATE_INPUT" not in invalid.text
            assert (await client.get("/health/ready")).json()["checks"]["memoryCandidates"]
    async with store.sessions.begin() as db:
        for table in (
            "ai_memory_candidate",
            "ai_memory_extraction",
            "ai_memory_suggestion_settings",
        ):
            await db.execute(text("DROP TABLE " + table))
    disabled = create_app(
        bot_settings, runtime_factory=lambda _settings, _memory: ExtractionRuntime(), store=store
    )
    async with disabled.router.lifespan_context(disabled):
        assert store.candidates is None
        result = await collect(
            PersistentChat(store, DurableContext(store), disabled.state.runtimes, 10),
            chat(session, "current"),
        )
        assert result[-1][0] == "done"


def test_candidate_global_flag_requires_c1_dependencies(settings):
    with pytest.raises(
        RuntimeError, match="require persistence, personalization and long-term memory"
    ):
        create_app(settings.model_copy(update={"memory_candidates_enabled": True}))


@pytest.mark.parametrize(
    "content",
    [
        "我的密码是example-secret",
        "password is example-secret",
        "Bearer opaque-credential-value",
        "mysql://user:example-secret@database/catgal",
    ],
)
def test_obvious_credential_formats_are_excluded(content):
    assert contains_credential(content)
    assert not contains_credential("喜欢密码解谜与温柔的日常剧情")
