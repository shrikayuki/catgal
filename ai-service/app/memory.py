# 阅读说明
# Redis 短期对话记忆及同会话生成租约。
# 键按用户、角色和会话分组，保存时限制历史长度与 TTL。

import asyncio
import logging
from collections.abc import AsyncIterator, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from pathlib import Path
from typing import Protocol, TypedDict
from uuid import uuid4

from redis.asyncio import Redis
from redis.exceptions import RedisError

from app.config import Settings
from app.errors import ConversationBusyError, ConversationLeaseLostError

logger = logging.getLogger(__name__)
LUA_DIRECTORY = Path(__file__).with_name("lua")
RELEASE_SCRIPT = (LUA_DIRECTORY / "release_conversation.lua").read_text(encoding="utf-8")
SAVE_EXCHANGE_SCRIPT = (LUA_DIRECTORY / "save_exchange.lua").read_text(encoding="utf-8")


class HistoryMessage(TypedDict):
    role: str
    content: str


class ChatMemory(Protocol):
    def conversation(
        self, user_id: int, girl_code: str, session_id: str
    ) -> AbstractAsyncContextManager[str]: ...

    async def load(self, user_id: int, girl_code: str, session_id: str) -> list[HistoryMessage]: ...

    async def save_exchange(
        self,
        user_id: int,
        girl_code: str,
        session_id: str,
        user_message: str,
        assistant_message: str,
        lease_token: str | None = None,
    ) -> None: ...

    async def health(self) -> bool: ...

    async def close(self) -> None: ...


class RedisChatMemory:
    KEY_PREFIX = "gal:chat:memory:"

    def __init__(
        self,
        client: Redis,
        *,
        max_history: int = 20,
        ttl_seconds: int = 7 * 24 * 60 * 60,
        lease_seconds: float = 105,
    ) -> None:
        self._client = client
        self._max_history = max_history
        self._ttl_seconds = ttl_seconds
        self._lease_milliseconds = int(lease_seconds * 1000)

    @classmethod
    def from_settings(cls, settings: Settings) -> "RedisChatMemory":
        redis_url = settings.redis_url_value
        if not redis_url:
            raise RuntimeError("AI_REDIS_URL is not configured")
        client = Redis.from_url(
            redis_url,
            decode_responses=True,
            socket_connect_timeout=5,
            socket_timeout=5,
            health_check_interval=30,
        )
        return cls(client, lease_seconds=settings.stream_timeout_seconds + 30)

    # NX 加租约将同一用户、角色、会话的并发生成串行化，token 用于释放时核对所有者。
    @asynccontextmanager
    async def conversation(
        self, user_id: int, girl_code: str, session_id: str
    ) -> AsyncIterator[str]:
        key = self.conversation_key(user_id, girl_code, session_id) + ":generation"
        token = uuid4().hex
        acquired = await self._client.set(key, token, nx=True, px=self._lease_milliseconds)
        if not acquired:
            raise ConversationBusyError()
        try:
            yield token
        finally:
            # Disconnect cancellation must not abandon an owned lock until its TTL.
            await asyncio.shield(self._release(key, token))

    # shield 让取消时的租约释放有机会完成，Lua 只删除仍属于本次 token 的锁。
    async def _release(self, key: str, token: str) -> None:
        try:
            async with asyncio.timeout(2):
                await self._client.eval(RELEASE_SCRIPT, 1, key, token)
        except (RedisError, TimeoutError) as exc:
            logger.warning("AI conversation lease release failed: %s", type(exc).__name__)

    @classmethod
    def conversation_key(cls, user_id: int, girl_code: str, session_id: str) -> str:
        conversation_id = f"user:{user_id}:girl:{girl_code}:session:{session_id}"
        return cls.KEY_PREFIX + conversation_id

    async def load(self, user_id: int, girl_code: str, session_id: str) -> list[HistoryMessage]:
        key = self.conversation_key(user_id, girl_code, session_id)
        values: Sequence[str] = await self._client.lrange(key, 0, -1)
        messages: list[HistoryMessage] = []
        for value in values:
            role, separator, content = value.partition(":")
            if separator and role in {"user", "assistant"}:
                messages.append({"role": role, "content": content})
        return messages

    # 成对追加用户与助手内容，并裁剪历史和续期；带租约时由 Lua 先核验所有者。
    async def save_exchange(
        self,
        user_id: int,
        girl_code: str,
        session_id: str,
        user_message: str,
        assistant_message: str,
        lease_token: str | None = None,
    ) -> None:
        key = self.conversation_key(user_id, girl_code, session_id)
        if lease_token is not None:
            committed = await self._client.eval(
                SAVE_EXCHANGE_SCRIPT,
                2,
                key + ":generation",
                key,
                lease_token,
                self._max_history,
                self._ttl_seconds,
                f"user:{user_message}",
                f"assistant:{assistant_message}",
            )
            if not committed:
                raise ConversationLeaseLostError()
            return
        async with self._client.pipeline(transaction=True) as pipeline:
            pipeline.rpush(key, f"user:{user_message}", f"assistant:{assistant_message}")
            pipeline.ltrim(key, -self._max_history, -1)
            pipeline.expire(key, self._ttl_seconds)
            await pipeline.execute()

    async def health(self) -> bool:
        return bool(await self._client.ping())

    async def close(self) -> None:
        await self._client.aclose()
