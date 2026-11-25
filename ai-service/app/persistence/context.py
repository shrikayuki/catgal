# 阅读说明
# 持久化消息的近期上下文读取与 Redis 加速，数据库版本决定缓存是否可用。

import asyncio
import json
import logging

from redis.exceptions import RedisError
from sqlalchemy.exc import SQLAlchemyError

from app.persistence.store import ChatStore, PreparedGeneration

logger = logging.getLogger(__name__)


class DurableContext:
    def __init__(self, store: ChatStore, redis=None):
        self.store = store
        self.redis = redis

    def key(self, prepared: PreparedGeneration, version: int | None = None) -> str:
        row = prepared.conversation
        # Versioned keys prevent a delayed cache write from replacing newer committed context.
        version = row.memory_version if version is None else version
        return f"gal:ai:context:{row.user_id}:{row.session_id}:v{version}"

    # 读取近期历史时将数据库版本与缓存版本比较，避免沿用旧上下文。
    async def load(self, prepared):
        key = self.key(prepared)
        if self.redis is not None:
            try:
                cached = await self.redis.get(key)
                if cached is not None:
                    turns = json.loads(cached)
                    if isinstance(turns, list) and all(
                        isinstance(item, dict)
                        and isinstance(item.get("id"), int)
                        and isinstance(item.get("user"), str)
                        and isinstance(item.get("assistant"), str)
                        for item in turns
                    ):
                        return self.messages(turns, prepared.user_message.id)
            except (RedisError, ValueError, TypeError):
                logger.warning("AI context cache read failed; loading durable history")
        turns = await self.store.turns(prepared)
        if self.redis is not None:
            try:
                await self.redis.set(key, json.dumps(turns, ensure_ascii=False), ex=604800)
            except RedisError:
                logger.warning("AI context cache write failed")
        return self.messages(turns, prepared.user_message.id)

    def messages(self, turns, before_id):
        visible = [turn for turn in turns if turn["id"] < before_id][-10:]
        return [
            message
            for turn in visible
            for message in (
                {"role": "user", "content": turn["user"]},
                {"role": "assistant", "content": turn["assistant"]},
            )
        ]

    # 数据库完成提交后再回暖短期上下文，缓存失败由后续读取回源处理。
    async def warm(self, prepared, version):
        if self.redis is None:
            return
        try:
            async with asyncio.timeout(2):
                turns = await self.store.turns(prepared)
                await self.redis.set(
                    self.key(prepared, version), json.dumps(turns, ensure_ascii=False), ex=604800
                )
        except (RedisError, SQLAlchemyError, TimeoutError):
            logger.warning("AI durable context remains available after cache warm failure")
