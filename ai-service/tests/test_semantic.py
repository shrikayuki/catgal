import asyncio
import json
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from pydantic import SecretStr, ValidationError
from test_bots import bot_settings as bot_settings
from test_bots import bots as bots
from test_bots import custom
from test_persistence_service import collect
from test_persistence_store import chat
from test_persistence_store import store as store

from app.game_tools import GameTools
from app.main import create_app
from app.persistence.context import DurableContext
from app.persistence.contracts import PersistenceError, SessionCreate
from app.persistence.service import PersistentChat
from app.security import compute_signature
from app.semantic.catalog import CatalogClient
from app.semantic.client import EmbeddingClient, vector
from app.semantic.index import VectorIndex
from app.semantic.service import SemanticSearch
from app.semantic.source import document_source
from app.semantic.worker import CatalogWorker

VECTOR = [1.0] + [0.0] * 1023
GAME = {
    "id": "42",
    "name": "QA Game",
    "introduction": "Public introduction",
    "tags": ["治愈"],
    "companies": ["QA"],
    "tagIds": ["2"],
    "companyIds": ["3"],
    "releaseDate": "2020-01-01",
    "languages": ["中文"],
    "platforms": ["win"],
    "resourceTypes": ["汉化"],
}
ITEM = {"gameId": "42", "version": "5", "deleted": False, "game": GAME}


@pytest.fixture
def semantic_settings(settings):
    return settings.model_copy(
        update={
            "semantic_search_enabled": True,
            "embedding_api_key": SecretStr("platform-embedding-key"),
            "semantic_es_url": "http://es.test",
            "java_tools_base_url": "http://java.test",
        }
    )


def fake_semantic(settings):
    embedding = SimpleNamespace(embed=AsyncMock(return_value=[VECTOR]), aclose=AsyncMock())
    index = SimpleNamespace(
        compatible=AsyncMock(),
        search=AsyncMock(return_value=[{"gameId": 42, "version": 5}]),
        document=AsyncMock(return_value=None),
        write=AsyncMock(),
        aclose=AsyncMock(),
    )

    def validate_existing(document, current):
        return VectorIndex.validate_existing(SimpleNamespace(settings=settings), document, current)

    index.validate_existing = validate_existing
    return SemanticSearch(settings, embedding=embedding, index=index)


@pytest.mark.asyncio
async def test_embedding_uses_platform_key_batch_order_and_finite_exact_dimensions(
    semantic_settings,
):
    requests = []

    def handler(request):
        requests.append(request)
        body = json.loads(request.content)
        assert body["model"] == "text-embedding-v4" and body["dimensions"] == 1024
        return httpx.Response(
            200,
            json={"data": [{"index": 1, "embedding": VECTOR}, {"index": 0, "embedding": VECTOR}]},
        )

    client = EmbeddingClient(semantic_settings, httpx.MockTransport(handler))
    try:
        assert await client.embed(["public game", "short query"]) == [VECTOR, VECTOR]
        assert requests[0].headers["authorization"] == "Bearer platform-embedding-key"
        assert requests[0].url.path.endswith("/embeddings")
        for texts in (["x"] * 11, ["x" * 2001], [" "]):
            with pytest.raises(ValueError):
                await client.embed(texts)
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    "values", [[0.0] * 1024, [float("nan")] * 1024, [True] * 1024, [float("inf")] * 1024, [1.0]]
)
def test_invalid_embedding_vectors_are_rejected(values):
    with pytest.raises(ValueError):
        vector(values, 1024)


def test_source_is_public_deterministic_and_compacted_without_losing_change_hash():
    first = {**ITEM, "game": {**GAME, "introduction": "甲" * 6000}}
    second = {**ITEM, "game": {**GAME, "introduction": "甲" * 5999 + "乙"}}
    old, text = document_source(first)
    new, following = document_source(second)
    assert len(text) == 2000 and "QA Game" in text and "治愈" in text and "QA" in text
    assert text == following and old["input_text_hash"] == new["input_text_hash"]
    assert old["source_text_hash"] != new["source_text_hash"]
    tombstone, content = document_source(
        {"gameId": "42", "version": "6", "deleted": True, "game": None}
    )
    assert content is None and tombstone == {"game_id": 42, "version": 6, "deleted": True}
    with pytest.raises(ValueError):
        document_source({**ITEM, "deleted": True})


@pytest.mark.asyncio
async def test_semantic_only_sends_short_query_and_validates_current_source_version(
    semantic_settings,
):
    semantic = fake_semantic(semantic_settings)
    validate = AsyncMock(return_value={"items": [GAME]})
    result = await semantic.search({"keyword": "治愈校园", "tagId": 2, "limit": 5}, validate)
    assert result["items"] == [GAME]
    semantic.embedding.embed.assert_awaited_once_with(["治愈校园"])
    validate.assert_awaited_once_with(
        "validate-games", {"candidates": [{"gameId": 42, "version": 5}], "tagId": 2, "limit": 5}
    )
    assert result["semanticVersions"] == {"42": 5}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "case", ["off", "key", "blank", "credential", "metadata", "es", "stale", "timeout"]
)
async def test_semantic_unavailable_always_returns_traditional_fallback(semantic_settings, case):
    semantic = fake_semantic(semantic_settings)
    keyword = "治愈"
    validate = AsyncMock(return_value={"items": [] if case == "stale" else [GAME]})
    if case == "off":
        semantic.settings = semantic.settings.model_copy(update={"semantic_search_enabled": False})
    elif case == "key":
        semantic.settings = semantic.settings.model_copy(update={"embedding_api_key": None})
    elif case == "blank":
        keyword = " "
    elif case == "credential":
        keyword = "API key: sk-sensitive12345678"
    elif case in ("metadata", "es"):
        semantic.index.compatible.side_effect = ValueError("PRIVATE_PROTOCOL")
    elif case == "timeout":

        async def slow(_):
            await asyncio.sleep(10)

        semantic.embedding.embed.side_effect = slow
        semantic.settings = semantic.settings.model_copy(update={"semantic_timeout_seconds": 0.03})
    assert await semantic.search({"keyword": keyword}, validate) is None


@pytest.mark.asyncio
async def test_semantic_cancellation_propagates_without_traditional_request(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    semantic.embedding.embed.side_effect = asyncio.CancelledError
    with pytest.raises(asyncio.CancelledError):
        await semantic.search({"keyword": "治愈"}, AsyncMock())


@pytest.mark.asyncio
async def test_game_tool_semantic_and_final_cards_use_exact_versions_and_filters(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    calls = []

    def handler(request):
        data = json.loads(request.content)
        calls.append((request.url.path, data))
        assert data["userId"] == 7
        return httpx.Response(200, json={"code": 200, "data": {"items": [GAME]}})

    async with GameTools(
        semantic_settings,
        7,
        semantic=semantic,
        use_semantic_search=True,
        transport=httpx.MockTransport(handler),
    ) as tools:
        found = await tools.execute("search_games", {"keyword": "治愈", "tagId": 2})
        assert found["items"][0]["id"] == "42"
        assert (await tools.recommendations())[0]["id"] == "42"
    assert len(calls) == 2 and all(path.endswith("validate-games") for path, _ in calls)
    assert all(
        body["candidates"] == [{"gameId": 42, "version": 5}] and body["tagId"] == 2
        for _, body in calls
    )


@pytest.mark.asyncio
async def test_catalog_service_hmac_has_no_user_identity(semantic_settings):
    def handler(request):
        assert "userId" not in json.loads(request.content)
        assert request.headers["x-catgal-signature"] == compute_signature(
            semantic_settings.hmac_secret_value,
            request.headers["x-catgal-timestamp"],
            request.headers["x-catgal-nonce"],
            "POST",
            request.url.path,
            request.content,
        )
        return httpx.Response(200, json={"code": 200, "data": {"pending": 1}})

    client = CatalogClient(semantic_settings, httpx.MockTransport(handler))
    try:
        assert await client.request("status", {}) == {"pending": 1}
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("case", ["equal", "newer", "mismatch"])
async def test_es_external_version_conflict_checks_content_before_ack(semantic_settings, case):
    body, _ = document_source(ITEM)
    body["model"] = semantic_settings.embedding_model
    body["vector"] = VECTOR
    current = {**body, "version": 6 if case == "newer" else 5}
    if case == "mismatch":
        current["input_text_hash"] = "different"

    def handler(request):
        if request.method == "PUT":
            assert request.url.params["version_type"] == "external"
            return httpx.Response(
                409, json={"error": {"type": "version_conflict_engine_exception"}}
            )
        return httpx.Response(200, json={"_source": current})

    index = VectorIndex(semantic_settings, httpx.MockTransport(handler))
    try:
        if case in ("mismatch", "newer"):
            with pytest.raises(ValueError):
                await index.write(body)
        else:
            await index.write(body)
    finally:
        await index.aclose()


class FakeCatalog:
    def __init__(self):
        self.pending = True
        self.calls = []

    async def request(self, operation, payload):
        self.calls.append((operation, payload))
        if operation == "pending":
            return {"items": [{"gameId": "42", "version": "4"}] if self.pending else []}
        if operation == "export":
            return {"items": [ITEM]}
        if operation in ("ack", "failed"):
            self.pending = False
            return {"stale": 0}
        return {}


@asynccontextmanager
async def available_lock(_settings):
    yield AsyncMock()


@pytest.mark.asyncio
async def test_worker_acknowledges_export_revision_and_reuses_identical_input(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    document, _ = document_source(ITEM)
    semantic.index.document.return_value = {
        **document,
        "version": 4,
        "model": "text-embedding-v4",
        "vector": VECTOR,
    }
    catalog = FakeCatalog()
    worker = CatalogWorker(semantic_settings, semantic, catalog, available_lock)
    result = await worker.drain()
    assert result["processed"] == 1 and not result["failed"]
    semantic.embedding.embed.assert_not_awaited()
    assert ("ack", {"items": [{"gameId": 42, "version": 5}]}) in catalog.calls
    assert semantic.index.write.call_args.args[0]["version"] == 5


@pytest.mark.asyncio
async def test_only_one_concurrent_worker_pays_for_same_embedding_batch(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    started, release = asyncio.Event(), asyncio.Event()
    held = False

    @asynccontextmanager
    async def lease(_settings):
        nonlocal held
        if held:
            yield None
            return
        held = True
        try:
            yield AsyncMock()
        finally:
            held = False

    async def embed(_texts):
        started.set()
        await release.wait()
        return [VECTOR]

    semantic.embedding.embed.side_effect = embed
    catalog = FakeCatalog()
    first = CatalogWorker(semantic_settings, semantic, catalog, lease)
    second = CatalogWorker(semantic_settings, semantic, catalog, lease)
    task = asyncio.create_task(first.drain())
    await asyncio.wait_for(started.wait(), 1)
    assert (await second.drain())["busy"]
    release.set()
    assert (await task)["processed"] == 1
    semantic.embedding.embed.assert_awaited_once()


@pytest.mark.asyncio
async def test_worker_failure_does_not_ack_and_reports_safe_code(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    semantic.embedding.embed.side_effect = ValueError("PRIVATE_PROVIDER")
    catalog = FakeCatalog()
    result = await CatalogWorker(semantic_settings, semantic, catalog, available_lock).drain()
    assert result["failed"] == 1
    assert not any(operation == "ack" for operation, _ in catalog.calls)
    assert (
        "failed",
        {"items": [{"gameId": 42, "version": 5}], "errorCode": "EMBEDDING_UNAVAILABLE"},
    ) in catalog.calls


@pytest.mark.asyncio
@pytest.mark.parametrize("case", ["equal_valid", "equal_mismatch", "newer", "invalid_vector"])
async def test_worker_existing_revision_must_pass_same_recovery_guard(semantic_settings, case):
    semantic = fake_semantic(semantic_settings)
    document, _ = document_source(ITEM)
    old = {**document, "model": "text-embedding-v4", "vector": VECTOR}
    if case == "equal_mismatch":
        old["source_text_hash"] = "wrong"
    elif case == "newer":
        old["version"] = 6
    elif case == "invalid_vector":
        old["vector"] = [1.0]
    semantic.index.document.return_value = old
    catalog = FakeCatalog()
    result = await CatalogWorker(semantic_settings, semantic, catalog, available_lock).drain()
    semantic.embedding.embed.assert_not_awaited()
    semantic.index.write.assert_not_awaited()
    if case == "equal_valid":
        assert result["processed"] == 1 and result["failed"] == 0
    else:
        assert result["failed"] == 1 and result["processed"] == 0
        assert not any(operation == "ack" for operation, _ in catalog.calls)
        assert (
            next(payload for operation, payload in catalog.calls if operation == "failed")[
                "errorCode"
            ]
            == "INDEX_PROTOCOL_ERROR"
        )


@pytest.mark.asyncio
async def test_opt_out_never_contacts_platform_embedding(semantic_settings):
    semantic = fake_semantic(semantic_settings)
    paths = []

    def handler(request):
        paths.append(request.url.path)
        return httpx.Response(200, json={"code": 200, "data": {"items": [GAME], "hasMore": False}})

    async with GameTools(
        semantic_settings, 7, semantic=semantic, transport=httpx.MockTransport(handler)
    ) as tools:
        assert (await tools.execute("search_games", {"keyword": "治愈"}))["items"]
    assert paths == ["/internal/ai/tools/search-games"]
    semantic.embedding.embed.assert_not_awaited()


@pytest.mark.asyncio
async def test_semantic_flag_only_changes_true_request_hash_and_keeps_old_runtime_signature(
    store, bots, bot_settings
):
    bot = await custom(bots)
    session = await store.create_session(SessionCreate(user_id=7, bot_id=int(bot["id"])))
    current = chat(session["sessionId"])
    old = await store.begin(current)
    await store.finish(old, "old answer")
    assert (await store.begin(current.model_copy(update={"use_semantic_search": False}))).replay
    with pytest.raises(PersistenceError, match="IDEMPOTENCY_CONFLICT"):
        await store.begin(
            current.model_copy(update={"use_semantic_search": True, "use_game_tools": True})
        )
    with pytest.raises(ValidationError):
        type(current).model_validate({**current.model_dump(), "use_semantic_search": True})
    # A missing semantic backend keeps ordinary chat readiness independent.
    config = bot_settings.model_copy(
        update={"semantic_search_enabled": True, "embedding_api_key": None}
    )

    class OldRuntime:
        async def generate_for_user(self, history, message, prompt, user_id, use_interests):
            yield "old compatible"

    app = create_app(config, runtime_factory=lambda _s, _m: OldRuntime(), store=store)
    async with app.router.lifespan_context(app):
        store.game_tools = True
        request = chat(session["sessionId"], "new").model_copy(update={"use_game_tools": True})
        events = await collect(
            PersistentChat(store, DurableContext(store), app.state.runtimes, 10), request
        )
        assert events[-1][0] == "done"
