# 阅读说明
# FastAPI 应用入口，启动时按配置装配模型、Redis、MySQL 和可选检索能力。
# lifespan 管理客户端生命周期，路由处理内部请求，关闭时统一释放连接。

import asyncio
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import aclosing, asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from redis.asyncio import Redis
from redis.exceptions import RedisError
from sqlalchemy.exc import SQLAlchemyError

from app.bots.api import router as bots_router
from app.bots.runtime import RuntimeProvider
from app.bots.store import BotStore
from app.candidates.api import router as candidates_router
from app.candidates.store import CandidateStore
from app.config import Settings
from app.errors import (
    ConversationBusyError,
    ConversationLeaseLostError,
    ModelCircuitOpenError,
)
from app.long_memory.api import router as long_memory_router
from app.long_memory.store import LongTermMemoryStore
from app.memory import ChatMemory, RedisChatMemory
from app.memory_vector.api import router as memory_vector_router
from app.memory_vector.index import PrivateMemoryIndex
from app.memory_vector.service import MemoryVectorService
from app.memory_vector.store import MemoryVectorStore
from app.memory_vector.worker import MemoryVectorWorker, automatic_memory_worker
from app.models import ChatStreamRequest
from app.persistence.api import router as persistence_router
from app.persistence.context import DurableContext
from app.persistence.contracts import PersistenceError
from app.persistence.database import create_database, session_factory
from app.persistence.store import ChatStore
from app.runtime import ChatRuntime, LangGraphChatRuntime
from app.security import NonceReplayGuard, verify_internal_request
from app.semantic.client import EmbeddingClient
from app.semantic.service import SemanticSearch
from app.semantic.worker import CatalogWorker, automatic_worker
from app.streaming import ConcurrencyGate, sse_event
from app.summaries.api import router as summaries_router
from app.summaries.store import SummaryStore

logger = logging.getLogger(__name__)


# 通过依赖注入支持真实服务和隔离测试，资源创建和关闭统一放入 lifespan。
def create_app(
    settings: Settings | None = None,
    runtime: ChatRuntime | None = None,
    memory: ChatMemory | None = None,
    runtime_factory: Callable[[Settings, ChatMemory], ChatRuntime] = LangGraphChatRuntime,
    store: ChatStore | None = None,
) -> FastAPI:
    app_settings = settings or Settings()
    if app_settings.personalization_enabled and not app_settings.persistence_enabled:
        raise RuntimeError("AI personalization requires persistence")
    if app_settings.game_tools_enabled and not app_settings.persistence_enabled:
        raise RuntimeError("AI game tools require persistence")
    if app_settings.long_term_memory_enabled and not (
        app_settings.persistence_enabled and app_settings.personalization_enabled
    ):
        raise RuntimeError("AI long-term memory requires persistence and personalization")
    if app_settings.session_summary_enabled and not (
        app_settings.persistence_enabled and app_settings.personalization_enabled
    ):
        raise RuntimeError("AI session summaries require persistence and personalization")
    if app_settings.memory_candidates_enabled and not (
        app_settings.persistence_enabled
        and app_settings.personalization_enabled
        and app_settings.long_term_memory_enabled
    ):
        raise RuntimeError(
            "AI memory candidates require persistence, personalization and long-term memory"
        )
    if (app_settings.memory_vector_enabled or app_settings.memory_vector_worker_enabled) and not (
        app_settings.persistence_enabled
        and app_settings.personalization_enabled
        and app_settings.long_term_memory_enabled
    ):
        raise RuntimeError(
            "AI private memory vectors require persistence, personalization and C1 memory"
        )

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        managed_memory = memory
        owns_memory = False
        if (
            not app_settings.persistence_enabled
            and managed_memory is None
            and app_settings.redis_url_value
        ):
            managed_memory = RedisChatMemory.from_settings(app_settings)
            owns_memory = True
        application.state.settings = app_settings
        application.state.runtime = runtime
        application.state.memory = managed_memory
        application.state.runtime_factory = runtime_factory
        semantic = None
        if app_settings.semantic_search_enabled:
            try:
                semantic = SemanticSearch(app_settings)
            except ValueError as exc:
                logger.warning("Semantic configuration unavailable: type=%s", type(exc).__name__)
        application.state.runtimes = RuntimeProvider(
            app_settings, runtime_factory, runtime, semantic=semantic
        )
        application.state.replay_guard = NonceReplayGuard()
        application.state.concurrency_gate = ConcurrencyGate(app_settings.max_concurrent_requests)
        engine = None
        durable_store = store
        cache = None
        if app_settings.persistence_enabled:
            if durable_store is None:
                engine = create_database(app_settings)
                durable_store = ChatStore(
                    session_factory(engine),
                    app_settings.chat_model,
                    app_settings.stream_timeout_seconds + 30,
                )
            if app_settings.personalization_enabled:
                durable_store.bots = BotStore(durable_store.sessions, app_settings)
            if app_settings.long_term_memory_enabled:
                durable_store.long_term_memory = LongTermMemoryStore(durable_store.sessions)
            if app_settings.session_summary_enabled:
                durable_store.summaries = SummaryStore(durable_store, app_settings)
            if app_settings.memory_candidates_enabled:
                durable_store.candidates = CandidateStore(durable_store, app_settings)
            if app_settings.redis_url_value:
                cache = Redis.from_url(
                    app_settings.redis_url_value,
                    decode_responses=True,
                    socket_connect_timeout=1,
                    socket_timeout=1,
                )
        application.state.store = durable_store
        private_service = None
        private_embedding = None
        owns_private_embedding = False
        if durable_store is not None:
            durable_store.game_tools = app_settings.game_tools_enabled
            if not app_settings.long_term_memory_enabled:
                durable_store.long_term_memory = None
            if not app_settings.session_summary_enabled:
                durable_store.summaries = None
            if not app_settings.memory_candidates_enabled:
                durable_store.candidates = None
            if app_settings.memory_vector_enabled or app_settings.memory_vector_worker_enabled:
                durable_store.memory_vectors = MemoryVectorStore(durable_store, app_settings)
                try:
                    if semantic is not None:
                        private_embedding = semantic.embedding
                    else:
                        private_embedding = EmbeddingClient(app_settings)
                        owns_private_embedding = True
                    private_service = MemoryVectorService(
                        durable_store.memory_vectors,
                        private_embedding,
                        PrivateMemoryIndex(app_settings),
                    )
                    if app_settings.memory_vector_enabled:
                        durable_store.long_term_memory.vector_service = private_service
                except ValueError as exc:
                    logger.warning(
                        "Private vector configuration unavailable: type=%s", type(exc).__name__
                    )
            else:
                durable_store.memory_vectors = None
        application.state.durable_context = DurableContext(durable_store, cache)
        worker = None
        worker_task = None
        if semantic is not None and app_settings.semantic_worker_enabled:
            worker = CatalogWorker(app_settings, semantic)
            worker_task = asyncio.create_task(automatic_worker(worker))
        private_worker_task = None
        if private_service is not None and app_settings.memory_vector_worker_enabled:
            private_worker_task = asyncio.create_task(
                automatic_memory_worker(
                    MemoryVectorWorker(durable_store.memory_vectors, private_service)
                )
            )
        try:
            yield
        finally:
            if private_worker_task is not None:
                private_worker_task.cancel()
                await asyncio.gather(private_worker_task, return_exceptions=True)
            if private_service is not None:
                await private_service.aclose()
            if owns_private_embedding and private_embedding is not None:
                await private_embedding.aclose()
            if worker_task is not None:
                worker_task.cancel()
                await asyncio.gather(worker_task, return_exceptions=True)
            if worker is not None:
                await worker.aclose()
            await application.state.runtimes.aclose()
            if semantic is not None:
                await semantic.aclose()
            if owns_memory and managed_memory is not None:
                await managed_memory.close()
            if cache is not None:
                await cache.aclose()
            if engine is not None:
                await engine.dispose()

    application = FastAPI(
        title="Catgal AI Internal Service",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.include_router(persistence_router)
    application.include_router(bots_router)
    application.include_router(long_memory_router)
    application.include_router(summaries_router)
    application.include_router(candidates_router)
    application.include_router(memory_vector_router)

    @application.exception_handler(RequestValidationError)
    async def validation_error(_request, exc):
        # FastAPI's default response includes rejected input, including submitted API keys.
        return JSONResponse(
            status_code=422,
            content={
                "detail": [
                    {"loc": error["loc"], "type": error["type"], "msg": "参数格式不正确"}
                    for error in exc.errors()
                ]
            },
        )

    @application.exception_handler(PersistenceError)
    async def persistence_error(_request, exc):
        return JSONResponse(
            status_code=exc.status, content={"code": exc.code, "message": exc.message}
        )

    @application.exception_handler(SQLAlchemyError)
    async def database_error(_request, _exc):
        logger.warning("AI database request unavailable")
        return JSONResponse(
            status_code=503,
            content={"code": "PERSISTENCE_UNAVAILABLE", "message": "持久化服务暂不可用"},
        )

    @application.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "alive"}

    @application.get("/health/ready")
    async def ready() -> JSONResponse:
        redis_ready = False
        chat_memory = application.state.memory
        if chat_memory is not None:
            try:
                redis_ready = await chat_memory.health()
            except Exception as exc:
                logger.warning("AI Redis readiness check failed: %s", type(exc).__name__)
        checks = {
            "serviceAuth": bool(app_settings.hmac_secret_value),
            "modelProvider": bool(app_settings.chat_api_key_value),
            "redisMemory": redis_ready,
        }
        if app_settings.persistence_enabled:
            try:
                checks["mysqlPersistence"] = await application.state.store.health()
            except SQLAlchemyError:
                checks["mysqlPersistence"] = False
            cache = application.state.durable_context.redis
            if cache is not None:
                try:
                    checks["redisMemory"] = bool(await cache.ping())
                except RedisError:
                    checks["redisMemory"] = False
            if app_settings.personalization_enabled:
                try:
                    checks["personalBots"] = await application.state.store.bots.health()
                except SQLAlchemyError:
                    checks["personalBots"] = False
                checks["modelProvider"] = checks["modelProvider"] or bool(
                    app_settings.provider_endpoints
                )
            if app_settings.long_term_memory_enabled:
                try:
                    memory_store = application.state.store.long_term_memory
                    checks["longTermMemory"] = await memory_store.health()
                except SQLAlchemyError:
                    checks["longTermMemory"] = False
            if app_settings.session_summary_enabled:
                try:
                    checks["sessionSummary"] = await application.state.store.summaries.health()
                except SQLAlchemyError:
                    checks["sessionSummary"] = False
            if app_settings.memory_candidates_enabled:
                try:
                    checks["memoryCandidates"] = await application.state.store.candidates.health()
                except SQLAlchemyError:
                    checks["memoryCandidates"] = False
            ready_checks = [
                checks["serviceAuth"],
                checks["modelProvider"],
                checks["mysqlPersistence"],
                checks.get("personalBots", True),
                checks.get("longTermMemory", True),
                checks.get("sessionSummary", True),
                checks.get("memoryCandidates", True),
            ]
        else:
            ready_checks = list(checks.values())
        code = status.HTTP_200_OK if all(ready_checks) else status.HTTP_503_SERVICE_UNAVAILABLE
        return JSONResponse(
            status_code=code,
            content={"status": "ready" if code == 200 else "not_ready", "checks": checks},
        )

    @application.post(
        "/internal/v1/chat/stream",
        dependencies=[Depends(verify_internal_request)],
        response_class=StreamingResponse,
    )
    async def chat_stream(payload: ChatStreamRequest, request: Request) -> StreamingResponse:
        if app_settings.persistence_enabled:
            raise PersistenceError("PERSISTENCE_REQUIRED", "请使用持久化聊天接口", 503)
        if len(payload.message) > app_settings.max_input_chars:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail="Message is too long",
            )

        chat_runtime = request.app.state.runtime
        if chat_runtime is None:
            chat_memory = request.app.state.memory
            if chat_memory is None:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="AI memory is not configured",
                )
            try:
                chat_runtime = request.app.state.runtime_factory(app_settings, chat_memory)
            except Exception as exc:
                logger.error("AI runtime initialization failed: %s", type(exc).__name__)
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="AI model provider is not configured",
                ) from exc
            request.app.state.runtime = chat_runtime

        async def event_stream() -> AsyncIterator[bytes]:
            gate: ConcurrencyGate = request.app.state.concurrency_gate
            acquired = await gate.try_acquire()
            if not acquired:
                yield sse_event("error", {"requestId": payload.request_id, "code": "SERVICE_BUSY"})
                return
            try:
                yield sse_event(
                    "meta",
                    {"requestId": payload.request_id, "sessionId": payload.session_id},
                )
                deadline = asyncio.get_running_loop().time() + app_settings.stream_timeout_seconds
                async with aclosing(
                    chat_runtime.stream(
                        user_id=payload.user_id,
                        girl_code=payload.girl_code,
                        session_id=payload.session_id,
                        message=payload.message,
                        system_prompt=payload.system_prompt,
                    )
                ) as stream:
                    while True:
                        try:
                            # Keep task cancellation scopes inside anext, never across ASGI yields.
                            async with asyncio.timeout_at(deadline):
                                token = await anext(stream)
                        except StopAsyncIteration:
                            break
                        yield sse_event("delta", {"content": token})
                yield sse_event("done", {"requestId": payload.request_id})
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error(
                    "AI stream failed: request_id=%s error=%s",
                    payload.request_id,
                    type(exc).__name__,
                )
                code = "MODEL_UPSTREAM_ERROR"
                if isinstance(exc, TimeoutError):
                    code = "GENERATION_TIMEOUT"
                elif isinstance(exc, ConversationBusyError):
                    code = "CONVERSATION_BUSY"
                elif isinstance(exc, ConversationLeaseLostError):
                    code = "CONVERSATION_LEASE_LOST"
                elif isinstance(exc, ModelCircuitOpenError):
                    code = "MODEL_CIRCUIT_OPEN"
                elif isinstance(exc, RedisError):
                    code = "MEMORY_UNAVAILABLE"
                yield sse_event("error", {"requestId": payload.request_id, "code": code})
            finally:
                await gate.release()

        return StreamingResponse(
            event_stream(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "X-Content-Type-Options": "nosniff",
            },
        )

    return application


app = create_app()
