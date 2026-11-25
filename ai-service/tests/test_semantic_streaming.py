import asyncio
import gzip
import json

import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from test_disconnect_live import running_server
from test_semantic import VECTOR
from test_semantic import semantic_settings as semantic_settings

from app.memory_vector.index import PrivateMemoryIndex
from app.semantic.client import EmbeddingClient, request_json
from app.semantic.index import VectorIndex


@pytest.mark.asyncio
@pytest.mark.parametrize("private", [False, True])
async def test_real_chunked_conflict_body_remains_readable_for_idempotent_index_write(
    semantic_settings, private
):
    server = FastAPI()
    requests = []
    identity = "memory_id" if private else "game_id"
    document = {
        identity: 42,
        "version": 5,
        "deleted": False,
        "model": "text-embedding-v4",
        "source_text_hash": "source",
        "input_text_hash": "input",
        "vector": VECTOR,
    }
    if private:
        document.update(user_id=7, bot_id=8, source_revision=1, consent_revision=1)

    @server.put("/{name}/_doc/{document_id}")
    async def conflict(request: Request):
        await request.body()
        requests.append("PUT")

        async def chunks():
            yield b'{"error":'
            await asyncio.sleep(0.01)
            yield b'{"type":"version_conflict_engine_exception"}}'

        return StreamingResponse(chunks(), status_code=409, media_type="application/json")

    @server.get("/{name}/_doc/{document_id}")
    async def existing():
        requests.append("GET")
        return {"_source": document}

    with running_server(server) as address:
        settings = semantic_settings.model_copy(update={"semantic_es_url": address})
        index = PrivateMemoryIndex(settings) if private else VectorIndex(settings)
        try:
            await index.write(document)
            await index.write(document)
        finally:
            await index.aclose()
    assert requests == ["PUT", "GET", "PUT", "GET"]


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["Arrearage", "PRIVATE_PROVIDER_CODE"])
async def test_real_gzip_embedding_error_body_is_readable_and_logs_only_safe_code(
    semantic_settings, caplog, code
):
    server = FastAPI()
    body = json.dumps({"error": {"code": code, "message": "PRIVATE_PROVIDER_MESSAGE"}}).encode()

    @server.post("/embeddings")
    async def unavailable(request: Request):
        await request.body()
        compressed = gzip.compress(body)

        async def chunks():
            yield compressed[:8]
            await asyncio.sleep(0.01)
            yield compressed[8:]

        return StreamingResponse(
            chunks(),
            status_code=400,
            media_type="application/json",
            headers={"Content-Encoding": "gzip", "Retry-After": "3"},
        )

    with running_server(server) as address:
        settings = semantic_settings.model_copy(update={"embedding_base_url": address})
        client = EmbeddingClient(settings)
        try:
            with pytest.raises(httpx.HTTPStatusError) as rejected:
                await client.embed(["PRIVATE_MEMORY_CONTENT"])
            response = rejected.value.response
            assert response.json()["error"]["code"] == code
            assert response.headers["Retry-After"] == "3"
            assert "content-encoding" not in response.headers
        finally:
            await client.aclose()
    assert "status=400" in caplog.text
    assert ("code=Arrearage" if code == "Arrearage" else "code=UPSTREAM_REJECTED") in caplog.text
    assert not any(
        secret in caplog.text
        for secret in (
            "PRIVATE_PROVIDER_CODE",
            "PRIVATE_PROVIDER_MESSAGE",
            "PRIVATE_MEMORY_CONTENT",
            "platform-embedding-key",
        )
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [200, 409])
async def test_real_streaming_json_size_limit_applies_to_success_and_error(status):
    server = FastAPI()

    @server.get("/large")
    async def large():
        async def chunks():
            yield b'{"payload":"'
            yield b"x" * 1000
            yield b'"}'

        return StreamingResponse(chunks(), status_code=status, media_type="application/json")

    with running_server(server) as address:
        async with httpx.AsyncClient(base_url=address, trust_env=False) as client:
            with pytest.raises(ValueError, match="exceeds limit"):
                await request_json(client, "GET", "/large", limit=64)
