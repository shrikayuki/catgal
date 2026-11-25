import asyncio
import json
import os
import socket
import threading
import time
from contextlib import contextmanager
from uuid import uuid4

import httpx
import pytest
import uvicorn
from conftest import TEST_SECRET
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import SecretStr
from redis.asyncio import Redis

from app.config import Settings
from app.main import create_app
from app.memory import RedisChatMemory
from app.security import compute_signature


@contextmanager
def running_server(app):
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    address = f"http://127.0.0.1:{listener.getsockname()[1]}"
    server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False))
    thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started:
            if not thread.is_alive() or time.monotonic() >= deadline:
                raise RuntimeError("Test HTTP server did not start")
            time.sleep(0.01)
        yield address
    finally:
        server.should_exit = True
        thread.join(10)
        listener.close()
        assert not thread.is_alive(), "Test HTTP server must stop"


@pytest.mark.skipif(os.getenv("CATGAL_AI_REDIS_TEST") != "1", reason="Explicit live Redis opt-in")
@pytest.mark.parametrize("stop", ["disconnect", "timeout", "complete"])
def test_http_termination_stops_provider_and_releases_redis_lease(stop):
    provider = FastAPI()
    provider_closed = threading.Event()

    @provider.post("/v1/chat/completions")
    async def completion():
        async def chunks():
            try:
                while True:
                    payload = {
                        "id": "chatcmpl-qa",
                        "object": "chat.completion.chunk",
                        "choices": [
                            {"index": 0, "delta": {"content": "partial"}, "finish_reason": None}
                        ],
                    }
                    yield f"data: {json.dumps(payload)}\n\n"
                    if stop == "complete":
                        payload["choices"][0]["delta"] = {}
                        payload["choices"][0]["finish_reason"] = "stop"
                        yield f"data: {json.dumps(payload)}\n\ndata: [DONE]\n\n"
                        return
                    await asyncio.sleep(0.05)
            finally:
                provider_closed.set()

        return StreamingResponse(chunks(), media_type="text/event-stream")

    session = "qa-" + uuid4().hex
    user_id = 9223372036854775806
    key = RedisChatMemory.conversation_key(user_id, "qa", session)

    async def verify_and_clean(verify=True):
        client = Redis.from_url(os.environ["AI_REDIS_URL"], decode_responses=True)
        try:
            if not verify:
                return
            for _ in range(100):
                if not await client.exists(key + ":generation"):
                    break
                await asyncio.sleep(0.05)
            assert not await client.exists(key + ":generation")
            if stop == "complete":
                assert await client.lrange(key, 0, -1) == ["user:test", "assistant:partial"]
            else:
                assert not await client.exists(key), (
                    "Partial replies must not become completed memory"
                )
        finally:
            await client.delete(key, key + ":generation")
            await client.aclose()

    try:
        with running_server(provider) as provider_url:
            settings = Settings(
                service_hmac_secret=SecretStr(TEST_SECRET),
                chat_api_key=SecretStr("fake-provider-test-key"),
                chat_base_url=provider_url + "/v1",
                redis_url=SecretStr(os.environ["AI_REDIS_URL"]),
                stream_timeout_seconds=1 if stop == "timeout" else 10,
                _env_file=None,
            )
            with running_server(create_app(settings=settings)) as service_url:
                path = "/internal/v1/chat/stream"
                body = json.dumps(
                    {
                        "requestId": "request-" + uuid4().hex,
                        "userId": user_id,
                        "girlCode": "qa",
                        "sessionId": session,
                        "message": "test",
                        "systemPrompt": "test",
                    }
                ).encode()
                timestamp, nonce = str(int(time.time())), uuid4().hex
                headers = {
                    "Content-Type": "application/json",
                    "X-Catgal-Timestamp": timestamp,
                    "X-Catgal-Nonce": nonce,
                    "X-Catgal-Signature": compute_signature(
                        TEST_SECRET, timestamp, nonce, "POST", path, body
                    ),
                }
                with httpx.Client(trust_env=False, timeout=5) as client:
                    with client.stream(
                        "POST", service_url + path, content=body, headers=headers
                    ) as response:
                        assert response.status_code == 200
                        saw_token = False
                        lines = []
                        for line in response.iter_lines():
                            lines.append(line)
                            if '"content":"partial"' in line:
                                saw_token = True
                                if stop == "disconnect":
                                    break
                        assert saw_token, "Expected a live token before terminating"
                        if stop == "timeout":
                            assert any("GENERATION_TIMEOUT" in line for line in lines)
                            assert "event: done" not in lines
                        elif stop == "complete":
                            assert "event: done" in lines
                            deltas = [
                                json.loads(line[6:])["content"]
                                for line in lines
                                if line.startswith('data: {"content"')
                            ]
                            assert "".join(deltas) == "partial"
                assert provider_closed.wait(5), "Provider HTTP stream must be cancelled"
                asyncio.run(verify_and_clean())
    finally:
        asyncio.run(verify_and_clean(verify=False))
