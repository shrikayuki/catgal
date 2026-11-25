# 阅读说明
# 语义查询先向量化，ES 候选再交 Java 按当前业务条件复核。
# 语义路径不可用时返回空降级信号，由工具继续常规查询。

import asyncio
import logging

from app.candidates.content import contains_credential
from app.semantic.client import EmbeddingClient
from app.semantic.index import VectorIndex

logger = logging.getLogger(__name__)
FILTERS = ("tagId", "companyId", "releaseYear", "language", "platform", "resourceType")


class SemanticSearch:
    def __init__(self, settings, *, embedding=None, index=None):
        self.settings = settings
        self.embedding = embedding or EmbeddingClient(settings)
        self.index = index or VectorIndex(settings)

    # 筛掉空查询与疑似凭据，向量候选经 Java 当前状态和筛选条件复核后才用于推荐。
    async def search(self, arguments, validate):
        keyword = (arguments.get("keyword") or "").strip()
        if (
            not self.settings.semantic_search_enabled
            or not self.settings.embedding_api_key
            or not keyword
            or len(keyword) > 200
            or contains_credential(keyword)
        ):
            return None
        try:
            async with asyncio.timeout(self.settings.semantic_timeout_seconds):
                await self.index.compatible()
                vectors = await self.embedding.embed([keyword])
                candidates = await self.index.search(vectors[0], arguments)
                if not candidates:
                    return None
                filters = {key: arguments[key] for key in FILTERS if arguments.get(key) is not None}
                result = await validate(
                    "validate-games",
                    {"candidates": candidates, "limit": arguments.get("limit", 5), **filters},
                )
                items = result.get("items")
                expected = {str(item["gameId"]): item["version"] for item in candidates}
                if not isinstance(items, list) or any(
                    str(item.get("id")) not in expected for item in items
                ):
                    raise ValueError("Unexpected validated game")
                if not items:
                    return None
                return {
                    "items": items,
                    "hasMore": len(candidates) > len(items),
                    "semanticVersions": expected,
                    "semanticFilters": filters,
                }
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning("Semantic search using traditional query: type=%s", type(exc).__name__)
            return None

    async def aclose(self):
        await self.embedding.aclose()
        await self.index.aclose()
