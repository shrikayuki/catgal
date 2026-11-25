import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from sqlalchemy import text
from test_bots import bot_settings as bot_settings
from test_bots import bots as bots
from test_bots import custom
from test_persistence_service import Runtime, post
from test_persistence_store import store as store
from test_semantic import VECTOR, available_lock

from app.bots.contracts import BotDelete
from app.long_memory.contracts import MemoryCreate, MemoryDelete, MemorySettingsUpdate
from app.long_memory.store import LongTermMemoryStore
from app.main import create_app
from app.memory_vector.contracts import MemoryVectorRequest, MemoryVectorUpdate
from app.memory_vector.index import PrivateMemoryIndex
from app.memory_vector.models import MemoryVectorOutbox
from app.memory_vector.service import MemoryVectorService
from app.memory_vector.store import MemoryVectorStore
from app.memory_vector.worker import MemoryVectorWorker
from app.persistence.contracts import PersistenceError


@pytest.fixture
def private(store, bots, bot_settings):
    config = bot_settings.model_copy(
        update={
            "long_term_memory_enabled": True,
            "memory_vector_enabled": True,
            "embedding_api_key": bot_settings.chat_api_key,
        }
    )
    store.long_term_memory = LongTermMemoryStore(store.sessions)
    vectors = MemoryVectorStore(store, config)
    store.memory_vectors = vectors
    embedding = SimpleNamespace(embed=AsyncMock(return_value=[VECTOR]), aclose=AsyncMock())
    index = SimpleNamespace(
        compatible=AsyncMock(),
        search=AsyncMock(return_value=[]),
        document=AsyncMock(return_value=None),
        write=AsyncMock(),
        aclose=AsyncMock(),
    )
    service = MemoryVectorService(vectors, embedding, index)
    store.long_term_memory.vector_service = service
    return service


def owner(bot_id, **values):
    return {"user_id": 7, "bot_id": bot_id, **values}


async def setup(store, bots, private, *, c1=True, granted=True):
    bot = int((await custom(bots))["id"])
    if c1:
        await store.long_term_memory.update_settings(
            MemorySettingsUpdate(**owner(bot, revision=1, memory_enabled=True))
        )
    if granted:
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=0, enabled=True))
        )
    return bot


async def remember(store, private, bot, content="QA_PRIVATE_FACT"):
    memory = await store.long_term_memory.create(
        MemoryCreate(**owner(bot, content=content, memory_type="preference"))
    )
    # SQLite isolated metadata has no MySQL triggers. Seed the equivalent event explicitly.
    async with store.sessions.begin() as db:
        now = await store.long_term_memory._now(db)
        db.add(
            MemoryVectorOutbox(
                memory_id=int(memory["id"]),
                user_id=7,
                bot_id=bot,
                next_attempt_at=now,
                dirty_since=now,
                update_time=now,
            )
        )
    return int(memory["id"])


async def hit(private, memory_id):
    source = await private.store.source(memory_id)
    return source.document(private.store.config.embedding_model)


@pytest.mark.asyncio
async def test_private_grant_revision_zero_ownership_and_c1_dependency(store, bots, private):
    bot = await setup(store, bots, private, c1=False, granted=False)
    assert await private.store.settings(MemoryVectorRequest(**owner(bot))) == {
        "botId": str(bot),
        "enabled": False,
        "revision": "0",
    }
    with pytest.raises(PersistenceError, match="MEMORY_VECTOR_REQUIRES_MEMORY"):
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=0, enabled=True))
        )
    await store.long_term_memory.update_settings(
        MemorySettingsUpdate(**owner(bot, revision=1, memory_enabled=True))
    )
    assert (
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=0, enabled=True))
        )
    )["revision"] == "1"
    with pytest.raises(PersistenceError, match="REVISION_CONFLICT"):
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=0, enabled=False))
        )
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await private.store.settings(MemoryVectorRequest(**owner(bot, user_id=8)))
    await bots.delete_bot(BotDelete(**owner(bot, revision=2)))
    assert (await private.store.settings(MemoryVectorRequest(**owner(bot))))["enabled"]
    await private.store.update_settings(MemoryVectorUpdate(**owner(bot, revision=1, enabled=False)))
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=2, enabled=True))
        )


@pytest.mark.asyncio
async def test_vector_recall_can_find_older_confirmed_memory_then_respects_both_switches(
    store, bots, private
):
    bot = await setup(store, bots, private)
    oldest = await remember(store, private, bot, "QA_OLD_CONFIRMED_GOAL")
    for index in range(10):
        await remember(store, private, bot, f"recent-{index}")
    private.index.search.return_value = [await hit(private, oldest)]
    assert "QA_OLD_CONFIRMED_GOAL" not in await store.long_term_memory.recall(7, bot)
    recalled = await store.long_term_memory.recall(7, bot, query="还记得最早目标吗")
    assert "QA_OLD_CONFIRMED_GOAL" in recalled
    await private.store.update_settings(MemoryVectorUpdate(**owner(bot, revision=1, enabled=False)))
    recent = await store.long_term_memory.recall(7, bot, query="还记得最早目标吗")
    assert "QA_OLD_CONFIRMED_GOAL" not in recent and "recent-9" in recent
    private.embedding.embed.assert_awaited_once()
    await store.long_term_memory.update_settings(
        MemorySettingsUpdate(**owner(bot, revision=2, memory_enabled=False))
    )
    assert await store.long_term_memory.recall(7, bot, query="query") == ""


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changed", ["owner", "bot", "version", "source", "grant", "hash", "deleted"]
)
async def test_hydration_revalidates_private_owner_status_and_versions(
    store, bots, private, changed
):
    bot = await setup(store, bots, private)
    memory_id = await remember(store, private, bot)
    candidate = await hit(private, memory_id)
    if changed == "deleted":
        await store.long_term_memory.delete(
            MemoryDelete(**owner(bot, memory_id=memory_id, revision=1))
        )
    else:
        field = {
            "owner": "user_id",
            "bot": "bot_id",
            "version": "version",
            "source": "source_revision",
            "grant": "consent_revision",
            "hash": "source_text_hash",
        }[changed]
        candidate[field] = "wrong-hash" if changed == "hash" else int(candidate[field]) + 1
    assert await private.store.hydrate(7, bot, 1, [candidate]) is None


@pytest.mark.asyncio
async def test_query_revocation_during_mapping_prevents_external_embedding(store, bots, private):
    bot = await setup(store, bots, private)

    async def revoke():
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=1, enabled=False))
        )

    private.index.compatible.side_effect = revoke
    assert await private.recall(7, bot, "QA current private query") is None
    private.embedding.embed.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["es", "provider", "timeout", "credential"])
async def test_private_failure_falls_back_to_c1_and_logs_no_body(
    store, bots, private, failure, caplog
):
    bot = await setup(store, bots, private)
    await remember(store, private, bot, "QA_PRIVATE_BODY")
    query = "password: do-not-send" if failure == "credential" else "query"
    if failure == "es":
        private.index.compatible.side_effect = ValueError("QA_PRIVATE_BODY")
    elif failure == "provider":
        private.embedding.embed.side_effect = RuntimeError("QA_PRIVATE_BODY")
    elif failure == "timeout":

        async def slow(_):
            await asyncio.sleep(10)

        private.embedding.embed.side_effect = slow
        private.store.config = private.store.config.model_copy(
            update={"memory_vector_timeout_seconds": 0.1}
        )
    assert "QA_PRIVATE_BODY" in await store.long_term_memory.recall(7, bot, query=query)
    assert "QA_PRIVATE_BODY" not in caplog.text and "do-not-send" not in caplog.text
    if failure == "credential":
        private.embedding.embed.assert_not_awaited()


@pytest.mark.asyncio
async def test_private_worker_sends_only_confirmed_authorized_fact_and_stores_no_text(
    store, bots, private
):
    bot = await setup(store, bots, private)
    await remember(store, private, bot, "QA_PRIVATE_SAVED_FACT")
    result = await MemoryVectorWorker(private.store, private, available_lock).drain()
    assert result["processed"] == 1 and result["failed"] == 0
    private.embedding.embed.assert_awaited_once_with(["preference\nQA_PRIVATE_SAVED_FACT"])
    document = private.index.write.call_args.args[0]
    assert "QA_PRIVATE_SAVED_FACT" not in json.dumps(document)
    assert not {"content", "text", "summary", "query"}.intersection(document)
    assert document["user_id"] == 7 and document["bot_id"] == bot and document["vector"] == VECTOR


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["grant", "c1", "archive", "delete", "sensitive", "global"])
async def test_private_ineligible_source_is_a_vector_free_tombstone(store, bots, private, reason):
    bot = await setup(store, bots, private)
    memory_id = await remember(
        store,
        private,
        bot,
        "password: qa-sensitive-secret" if reason == "sensitive" else "QA_PRIVATE_SAVED_FACT",
    )
    if reason == "grant":
        await private.store.update_settings(
            MemoryVectorUpdate(**owner(bot, revision=1, enabled=False))
        )
    elif reason == "c1":
        await store.long_term_memory.update_settings(
            MemorySettingsUpdate(**owner(bot, revision=2, memory_enabled=False))
        )
    elif reason == "archive":
        await bots.delete_bot(BotDelete(**owner(bot, revision=2)))
    elif reason == "delete":
        await store.long_term_memory.delete(
            MemoryDelete(**owner(bot, memory_id=memory_id, revision=1))
        )
    elif reason == "global":
        private.store.config = private.store.config.model_copy(
            update={"memory_vector_enabled": False}
        )
    await MemoryVectorWorker(private.store, private, available_lock).drain()
    private.embedding.embed.assert_not_awaited()
    document = private.index.write.call_args.args[0]
    assert document["deleted"] and "vector" not in document
    assert "QA_PRIVATE_SAVED_FACT" not in json.dumps(
        document
    ) and "qa-sensitive-secret" not in json.dumps(document)


@pytest.mark.asyncio
@pytest.mark.parametrize("when", ["document", "embedding"])
async def test_revoked_source_before_or_after_embedding_never_writes_old_vector(
    store, bots, private, when
):
    bot = await setup(store, bots, private)
    await remember(store, private, bot)
    revoked = False

    async def revoke():
        nonlocal revoked
        if not revoked:
            revoked = True
            await private.store.update_settings(
                MemoryVectorUpdate(**owner(bot, revision=1, enabled=False))
            )
            await private.store.rebuild()

    async def document(_id):
        if when == "document":
            await revoke()
        return None

    async def embedding(_texts):
        await revoke()
        return [VECTOR]

    private.index.document.side_effect = document
    private.embedding.embed.side_effect = embedding
    await MemoryVectorWorker(private.store, private, available_lock).drain()
    assert private.index.write.await_count >= 1
    assert all(
        call.args[0]["deleted"] and "vector" not in call.args[0]
        for call in private.index.write.call_args_list
    )
    if when == "document":
        private.embedding.embed.assert_not_awaited()


@pytest.mark.asyncio
async def test_private_index_has_distinct_metadata_and_owner_filters(settings):
    requests = []

    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={"hits": {"hits": []}, "_shards": {"failed": 0}})

    index = PrivateMemoryIndex(settings, httpx.MockTransport(handler))
    try:
        assert index.metadata()["corpus"] == "private_confirmed_memories"
        assert (
            "content" not in index.mapping_properties()
            and "game_id" not in index.mapping_properties()
        )
        await index.search(VECTOR, 7, 8, 9)
        filters = requests[0]["knn"]["filter"]["bool"]["filter"]
        assert {"term": {"user_id": 7}} in filters and {"term": {"bot_id": 8}} in filters
        assert {"term": {"consent_revision": 9}} in filters
    finally:
        await index.aclose()


@pytest.mark.asyncio
async def test_private_api_auth_strict_validation_and_v15_off_compatibility(
    store, bots, private, bot_settings
):
    bot = await setup(store, bots, private, granted=False)
    app = create_app(private.store.config, runtime=Runtime(), store=store)
    body = {"userId": 7, "botId": str(bot)}
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            path = "/internal/v1/memory-vector/settings/get"
            assert (await post(client, path, body, signed=False)).status_code == 401
            assert (await post(client, path, body)).json()["revision"] == "0"
            response = await post(
                client,
                "/internal/v1/memory-vector/settings/update",
                {**body, "enabled": True, "revision": 0, "secret": "QA_PRIVATE_INPUT"},
            )
            assert response.status_code == 422 and "QA_PRIVATE_INPUT" not in response.text
    async with store.sessions.begin() as db:
        await db.execute(text("DROP TABLE ai_memory_vector_settings"))
        await db.execute(text("DROP TABLE ai_memory_vector_outbox"))
    app = create_app(bot_settings, runtime=Runtime(), store=store)
    async with app.router.lifespan_context(app):
        assert store.memory_vectors is None
        assert await store.bots.health()


def test_private_feature_requires_c1_dependencies(settings):
    with pytest.raises(RuntimeError, match="require persistence, personalization and C1"):
        create_app(settings.model_copy(update={"memory_vector_enabled": True}))
