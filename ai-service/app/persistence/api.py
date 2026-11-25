# 阅读说明
# 会话与消息内部路由，将 Java 已认证身份交给 ChatStore 和持久化生成编排。

import asyncio
import logging
from contextlib import aclosing

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from sqlalchemy.exc import SQLAlchemyError

from app.persistence.contracts import (
    MessageQuery,
    PersistenceError,
    PersistentChatRequest,
    SessionCreate,
    SessionQuery,
    SessionRequest,
)
from app.persistence.service import PersistentChat
from app.security import verify_internal_request
from app.streaming import sse_event

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/internal/v1", dependencies=[Depends(verify_internal_request)])


def require_store(request: Request):
    if not request.app.state.settings.persistence_enabled or request.app.state.store is None:
        raise PersistenceError("PERSISTENCE_UNAVAILABLE", "AI持久化服务未启用", 503)
    return request.app.state.store


@router.post("/sessions/create")
async def create_session(payload: SessionCreate, request: Request):
    return await require_store(request).create_session(payload)


@router.post("/sessions/list")
async def list_sessions(payload: SessionQuery, request: Request):
    return await require_store(request).list_sessions(payload)


@router.post("/sessions/get")
async def get_session(payload: SessionRequest, request: Request):
    return await require_store(request).get_session(payload)


@router.post("/sessions/delete")
async def delete_session(payload: SessionRequest, request: Request):
    return await require_store(request).delete_session(payload)


@router.post("/messages/list")
async def list_messages(payload: MessageQuery, request: Request):
    return await require_store(request).list_messages(payload)


@router.post("/chat/persistent")
async def chat(payload: PersistentChatRequest, request: Request):
    store = require_store(request)
    settings = request.app.state.settings
    if payload.content is not None and len(payload.content) > settings.max_input_chars:
        raise PersistenceError("MESSAGE_TOO_LONG", "消息过长", 413)
    service = PersistentChat(
        store,
        request.app.state.durable_context,
        request.app.state.runtimes,
        settings.stream_timeout_seconds,
    )

    async def events():
        gate = request.app.state.concurrency_gate
        if not await gate.try_acquire():
            yield sse_event("error", {"requestId": payload.request_id, "code": "SERVICE_BUSY"})
            return
        try:
            async with aclosing(service.events(payload)) as stream:
                async for event, data in stream:
                    yield sse_event(event, data)
        except asyncio.CancelledError:
            raise
        except PersistenceError as exc:
            yield sse_event("error", {"requestId": payload.request_id, "code": exc.code})
        except SQLAlchemyError:
            logger.warning("AI persistence request unavailable")
            yield sse_event(
                "error", {"requestId": payload.request_id, "code": "PERSISTENCE_UNAVAILABLE"}
            )
        finally:
            await gate.release()

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "X-Content-Type-Options": "nosniff",
        },
    )
