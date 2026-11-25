# 阅读说明
# 持久化聊天生成的编排层，先建立消息记录，再流式调用并周期保存检查点。
# 成功、失败、取消各自进入终态，恢复依赖数据库生成租约与 token 核验。

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing

import anyio
from sqlalchemy.exc import SQLAlchemyError

from app.bots.runtime import RuntimeProvider
from app.candidates.runtime import extract_candidates
from app.errors import ModelCircuitOpenError
from app.game_tools import RecommendationBatch
from app.persistence.context import DurableContext
from app.persistence.contracts import PersistenceError, PersistentChatRequest
from app.persistence.store import ChatStore
from app.summaries.runtime import recall_summary

logger = logging.getLogger(__name__)


class PersistentChat:
    def __init__(self, store: ChatStore, context: DurableContext, runtime, timeout: float):
        self.store = store
        self.context = context
        self.runtime = runtime
        self.timeout = timeout

    # 先读取幂等准备结果，已完成请求回放持久化内容，新请求才调用模型。
    async def events(self, request: PersistentChatRequest) -> AsyncIterator[tuple[str, dict]]:
        prepared = await self.store.begin(request)
        assistant = prepared.assistant
        metadata = {
            "requestId": request.request_id,
            "sessionId": request.session_id,
            "messageId": str(assistant.id),
            "userMessageId": str(prepared.user_message.id),
            "replayed": prepared.replay,
            "status": assistant.status,
            "model": assistant.model,
        }
        parts = []
        recommendations = []
        finished = prepared.replay
        try:
            yield "meta", metadata
            if prepared.replay:
                if assistant.content:
                    yield "delta", {"content": assistant.content}
                saved_items = assistant.__dict__.get("recommendations")
                if saved_items:
                    yield "recommendations", {"requestId": request.request_id, "items": saved_items}
                if assistant.status == "completed":
                    yield "done", {"requestId": request.request_id}
                else:
                    yield (
                        "error",
                        {
                            "requestId": request.request_id,
                            "code": assistant.error_code or "GENERATION_INTERRUPTED",
                        },
                    )
                return
            deadline = asyncio.get_running_loop().time() + self.timeout
            async with asyncio.timeout_at(deadline):
                history = await self.context.load(prepared)
            system_prompt = prepared.system_prompt
            if self.store.long_term_memory is not None and prepared.profile is not None:
                vector_query = {}
                lookup_timeout = 2
                if self.store.memory_vectors is not None:
                    vector_query = {"query": prepared.user_message.content}
                    lookup_timeout = 3
                try:
                    async with asyncio.timeout_at(
                        min(deadline, asyncio.get_running_loop().time() + lookup_timeout)
                    ):
                        recalled = await self.store.long_term_memory.recall(
                            request.user_id, prepared.profile.bot_id, **vector_query
                        )
                except (SQLAlchemyError, TimeoutError) as exc:
                    logger.warning(
                        "AI long-term memory unavailable: bot_id=%s type=%s",
                        prepared.profile.bot_id,
                        type(exc).__name__,
                    )
                    raise PersistenceError(
                        "LONG_TERM_MEMORY_UNAVAILABLE", "长期记忆暂不可用，请稍后重试", 503
                    ) from None
                # Keep the immutable persona snapshot and prompt hash unchanged.
                system_prompt += recalled
            checkpoint_at = asyncio.get_running_loop().time()
            pending_chars = 0
            runtimes = (
                self.runtime
                if isinstance(self.runtime, RuntimeProvider)
                else RuntimeProvider(None, None, self.runtime)
            )
            async with runtimes.use(prepared.profile) as runtime:
                if self.store.summaries is not None and prepared.profile is not None:
                    system_prompt += await recall_summary(
                        self.store.summaries, prepared, runtime, deadline
                    )
                if self.store.candidates is not None and prepared.profile is not None:
                    await extract_candidates(self.store.candidates, prepared, runtime, deadline)
                if (
                    self.store.game_tools
                    and request.use_game_tools
                    and hasattr(runtime, "generate_for_user")
                ):
                    generation = runtime.generate_for_user(
                        history,
                        prepared.user_message.content,
                        system_prompt,
                        request.user_id,
                        request.use_interests,
                        **({"use_semantic_search": True} if request.use_semantic_search else {}),
                    )
                else:
                    generation = runtime.generate(
                        history, prepared.user_message.content, system_prompt
                    )
                async with aclosing(generation) as stream:
                    while True:
                        try:
                            async with asyncio.timeout_at(deadline):
                                chunk = await anext(stream)
                        except StopAsyncIteration:
                            break
                        if isinstance(chunk, RecommendationBatch):
                            recommendations = chunk.items
                            continue
                        parts.append(chunk)
                        pending_chars += len(chunk)
                        now = asyncio.get_running_loop().time()
                        if pending_chars >= 256 or now - checkpoint_at >= 1:
                            async with asyncio.timeout_at(deadline):
                                await self.store.checkpoint(prepared, "".join(parts))
                            checkpoint_at = now
                            pending_chars = 0
                        yield "delta", {"content": chunk}
            content = "".join(parts)
            if not content:
                raise PersistenceError("MODEL_UPSTREAM_ERROR", "模型返回空回答", 503)
            async with asyncio.timeout_at(deadline):
                version = await self.store.finish(
                    prepared, content, recommendations=recommendations
                )
            if version is None:
                raise PersistenceError("GENERATION_INTERRUPTED", "生成状态已失效")
            finished = True
            await self.context.warm(prepared, version)
            if recommendations:
                yield "recommendations", {"requestId": request.request_id, "items": recommendations}
            yield "done", {"requestId": request.request_id}
        except (asyncio.CancelledError, GeneratorExit):
            raise
        except Exception as exc:
            code = "MODEL_UPSTREAM_ERROR"
            if isinstance(exc, TimeoutError):
                code = "GENERATION_TIMEOUT"
            elif isinstance(exc, ModelCircuitOpenError):
                code = "MODEL_CIRCUIT_OPEN"
            elif isinstance(exc, PersistenceError):
                code = exc.code
            elif isinstance(exc, SQLAlchemyError):
                code = "PERSISTENCE_UNAVAILABLE"
            if not finished:
                finished = await self._close(prepared, "".join(parts), "failed", code)
            logger.warning(
                "AI durable generation failed: code=%s type=%s", code, type(exc).__name__
            )
            yield "error", {"requestId": request.request_id, "code": code}
        finally:
            if not finished:
                await self._close(prepared, "".join(parts), "interrupted", "GENERATION_INTERRUPTED")

    # 屏蔽取消信号并设最终写库超时，失败时由租约过期恢复后续状态。
    async def _close(self, prepared, content, status, code) -> bool:
        try:
            # Persist cancellation state despite ASGI's repeated cancellation signals.
            with anyio.CancelScope(shield=True):
                async with asyncio.timeout(5):
                    result = await self.store.finish(prepared, content, status, code)
            return result is not None
        except (SQLAlchemyError, TimeoutError):
            logger.warning("AI generation finalization deferred to lease recovery")
            return False
