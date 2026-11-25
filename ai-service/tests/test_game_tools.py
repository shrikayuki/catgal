import asyncio
import json

import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from test_disconnect_live import running_server
from test_persistence_service import post
from test_persistence_store import chat, conversation
from test_persistence_store import store as store

from app.game_tools import GameTools, RecommendationBatch
from app.main import create_app
from app.persistence.context import DurableContext
from app.persistence.contracts import MessageQuery, PersistenceError
from app.persistence.service import PersistentChat
from app.security import compute_signature

GAME = {"id": "42", "name": "QA Game", "introduction": "QA summary", "tags": ["治愈"]}


@pytest.mark.asyncio
async def test_disabled_tools_fail_explicitly_before_model_or_message_creation(store):
    session = await conversation(store)
    with pytest.raises(PersistenceError, match="GAME_TOOLS_UNAVAILABLE"):
        await store.begin(chat(session).model_copy(update={"use_game_tools": True}))
    history = await store.list_messages(MessageQuery(user_id=7, session_id=session))
    assert history["items"] == []


@pytest.mark.asyncio
async def test_tools_inject_trusted_identity_and_sign_exact_body(settings):
    calls = []

    def handle(request):
        calls.append(request)
        body = json.loads(request.content)
        assert body["userId"] == 7
        assert request.headers["x-catgal-signature"] == compute_signature(
            settings.hmac_secret_value,
            request.headers["x-catgal-timestamp"],
            request.headers["x-catgal-nonce"],
            "POST",
            request.url.path,
            request.content,
        )
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "items": [{**GAME, "privateUrl": "private-value", "password": "private-secret"}]
                },
            },
        )

    async with GameTools(settings, 7, transport=httpx.MockTransport(handle)) as tools:
        result = await tools.execute("search_games", {"limit": 1})
        assert len(result["items"]) == 1
        assert "private-" not in str(result)
        assert (await tools.execute("search_games", {"userId": 8}))[
            "error"
        ] == "GAME_TOOL_UNAVAILABLE"
        assert len(calls) == 1


@pytest.mark.asyncio
async def test_interest_tool_requires_explicit_authorization_and_caps_calls(settings):
    calls = []

    def handle(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "favorites": [GAME],
                    "reviews": [],
                    "sampled": True,
                    "sourceStatus": "database_fallback",
                },
            },
        )

    async with GameTools(settings, 7, transport=httpx.MockTransport(handle)) as tools:
        assert "get_my_interests" not in str(tools.schemas())
        assert (await tools.execute("get_my_interests", {}))["error"] == "TOOL_NOT_ALLOWED"
        assert calls == []
    async with GameTools(settings, 7, True, transport=httpx.MockTransport(handle)) as tools:
        result = await tools.execute("get_my_interests", {"limit": 1})
        assert result["sampled"] and result["sourceStatus"] == "database_fallback"
        for _ in range(settings.max_tool_calls - 1):
            await tools.execute("get_my_interests", {"limit": 11})
        with pytest.raises(PersistenceError, match="TOOL_BUDGET_EXCEEDED"):
            await tools.execute("get_my_interests", {})


@pytest.mark.asyncio
async def test_cards_are_revalidated_and_never_trust_changed_ids(settings):
    status = 200

    def handle(request):
        if request.url.path.endswith("search-games"):
            return httpx.Response(200, json={"code": 200, "data": {"items": [GAME]}})
        return httpx.Response(status, json={"code": status, "data": {**GAME, "id": "43"}})

    async with GameTools(settings, 7, transport=httpx.MockTransport(handle)) as tools:
        await tools.execute("search_games", {})
        assert await tools.recommendations() == []
        status = 404
        assert await tools.recommendations() == []


@pytest.mark.asyncio
async def test_signed_http_request_cancels_with_its_parent(settings):
    cancelled = asyncio.Event()

    async def handle(_request):
        try:
            await asyncio.sleep(60)
        finally:
            cancelled.set()

    async with GameTools(settings, 7, transport=httpx.MockTransport(handle)) as tools:
        pending = asyncio.create_task(tools.execute("search_games", {}))
        await asyncio.sleep(0.02)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert cancelled.is_set()


@pytest.mark.asyncio
async def test_cards_persist_with_answer_and_replay_without_model_or_gateway(store):
    class Runtime:
        calls = 0

        async def generate_for_user(self, _history, _message, _prompt, user_id, use_interests):
            self.calls += 1
            assert user_id == 7 and use_interests
            yield "QA recommendation"
            yield RecommendationBatch([{"id": "42", "name": "QA Game"}])

        async def generate(self, *_args):
            raise AssertionError("Must route through trusted user context")
            yield

    store.game_tools = True
    session = await conversation(store)
    runtime = Runtime()
    request = chat(session).model_copy(update={"use_interests": True, "use_game_tools": True})
    service = PersistentChat(store, DurableContext(store), runtime, 5)
    first = [event async for event in service.events(request)]
    replay = [event async for event in service.events(request)]
    assert [item[0] for item in first] == ["meta", "delta", "recommendations", "done"]
    assert replay[-2][1]["items"] == first[-2][1]["items"]
    assert runtime.calls == 1
    history = await store.list_messages(MessageQuery(user_id=7, session_id=session))
    assert history["items"][-1]["recommendations"][0]["id"] == "42"
    with pytest.raises(PersistenceError, match="IDEMPOTENCY_CONFLICT"):
        await store.begin(request.model_copy(update={"use_interests": False}))


@pytest.mark.asyncio
async def test_real_langgraph_tool_loop_calls_signed_gateway_and_replays_durably(store, settings):
    gateway, provider = FastAPI(), FastAPI()
    model_calls, gateway_calls = [], []

    @gateway.post("/internal/ai/tools/{operation}")
    async def tool(operation: str, request: Request):
        body = await request.body()
        assert json.loads(body)["userId"] == 7
        assert request.headers["x-catgal-signature"] == compute_signature(
            settings.hmac_secret_value,
            request.headers["x-catgal-timestamp"],
            request.headers["x-catgal-nonce"],
            "POST",
            request.url.path,
            body,
        )
        gateway_calls.append(operation)
        data = {"items": [GAME]} if operation == "search-games" else GAME
        return JSONResponse({"code": 200, "data": data})

    @provider.post("/v1/chat/completions")
    async def completion(request: Request):
        payload = await request.json()
        model_calls.append(payload)
        has_result = any(
            message["role"] == "tool" for message in payload["messages"]
        ) or not payload.get("tools")

        async def chunks():
            delta = (
                {"content": "推荐 QA Game，可以查看本站资料。"}
                if has_result
                else {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_search",
                            "type": "function",
                            "function": {
                                "name": "search_games",
                                "arguments": '{"keyword":"治愈","limit":1}',
                            },
                        }
                    ]
                }
            )
            chunk = {
                "id": "chatcmpl-qa",
                "object": "chat.completion.chunk",
                "choices": [{"index": 0, "delta": delta, "finish_reason": None}],
            }
            yield f"data: {json.dumps(chunk)}\n\n"
            chunk["choices"][0].update(
                delta={}, finish_reason="stop" if has_result else "tool_calls"
            )
            yield f"data: {json.dumps(chunk)}\n\ndata: [DONE]\n\n"

        return StreamingResponse(chunks(), media_type="text/event-stream")

    with running_server(gateway) as gateway_url, running_server(provider) as provider_url:
        configured = settings.model_copy(
            update={
                "persistence_enabled": True,
                "game_tools_enabled": True,
                "redis_url": None,
                "java_tools_base_url": gateway_url,
                "chat_base_url": provider_url + "/v1",
            }
        )
        app = create_app(configured, store=store)
        session = await conversation(store)
        payload = (
            chat(session)
            .model_copy(update={"use_game_tools": True})
            .model_dump(by_alias=True, exclude_none=True)
        )
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                first = await post(client, "/internal/v1/chat/persistent", payload)
                assert "event: done" in first.text and '"items":[{"id":"42"' in first.text
                replay = await post(client, "/internal/v1/chat/persistent", payload)
                assert '"replayed":true' in replay.text and "event: recommendations" in replay.text
                assert len(model_calls) == 2 and gateway_calls == ["search-games", "game-detail"]
                assert "get_my_interests" not in str(model_calls[0]["tools"])
                text_only = chat(session, "text-only-key").model_dump(
                    by_alias=True, exclude_none=True
                )
                plain = await post(client, "/internal/v1/chat/persistent", text_only)
                assert "event: done" in plain.text
                assert len(model_calls) == 3 and not model_calls[-1].get("tools")
                assert gateway_calls == ["search-games", "game-detail"]
