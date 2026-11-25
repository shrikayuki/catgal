# 阅读说明
# 私有记忆向量召回编排，embedding 前校验授权，ES 命中后回库复核有效记忆。
# 网络失败或无有效候选时返回降级信号，调用方可使用数据库近期记忆。

import asyncio
import logging

from app.candidates.content import contains_credential
from app.long_memory.store import memory_context

logger = logging.getLogger(__name__)


class MemoryVectorService:
    def __init__(self, store, embedding, index):
        self.store = store
        self.embedding = embedding
        self.index = index

    # 先核验本人授权版本，再进行 embedding 与带隔离过滤的检索，命中仍须回库复核。
    async def recall(self, user_id, bot_id, query):
        if (
            not self.store.config.memory_vector_enabled
            or not query.strip()
            or contains_credential(query)
            or not self.store.config.embedding_api_key
        ):
            return None
        try:
            async with asyncio.timeout(self.store.config.memory_vector_timeout_seconds):
                revision = await self.store.consent(user_id, bot_id)
                if revision is None:
                    return None
                await self.index.compatible()
                if await self.store.consent(user_id, bot_id) != revision:
                    return None
                vectors = await self.embedding.embed([query[:2000]])
                hits = await self.index.search(vectors[0], user_id, bot_id, revision)
                facts = await self.store.hydrate(user_id, bot_id, revision, hits)
                return memory_context(facts) if facts else None
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning("Private memory vector fallback: type=%s", type(exc).__name__)
            return None

    async def aclose(self):
        await self.index.aclose()
