# 阅读说明
# 游戏向量同步 worker，MySQL 命名锁协调实例，按源版本写 ES 后确认任务。
# 输入摘要和模型相同可复用向量，失败保留版本化任务供重试。

import argparse
import asyncio
import hashlib
import json
import logging
from contextlib import asynccontextmanager

import anyio
from sqlalchemy import text

from app.config import Settings
from app.persistence.database import create_database
from app.semantic.catalog import CatalogClient
from app.semantic.client import vector
from app.semantic.service import SemanticSearch
from app.semantic.source import document_source

logger = logging.getLogger(__name__)


@asynccontextmanager
async def worker_lock(settings):
    """A connection-scoped mutex coordinates CLI and automatic workers across processes."""
    engine = create_database(settings)
    key = "catgal:vector:" + hashlib.sha256(settings.semantic_index_name.encode()).hexdigest()[:40]
    try:
        async with engine.connect() as connection:
            acquired = await connection.scalar(text("SELECT GET_LOCK(:key,0)"), {"key": key})
            await connection.commit()

            async def check():
                owned = await connection.scalar(
                    text("SELECT IS_USED_LOCK(:key)=CONNECTION_ID()"), {"key": key}
                )
                await connection.commit()
                if owned != 1:
                    raise RuntimeError("Semantic worker lock lost")

            try:
                yield check if acquired == 1 else None
            finally:
                if acquired == 1:
                    with anyio.CancelScope(shield=True):
                        with anyio.move_on_after(2):
                            await connection.execute(
                                text("SELECT RELEASE_LOCK(:key)"), {"key": key}
                            )
                            await connection.commit()
    finally:
        await engine.dispose()


class CatalogWorker:
    def __init__(self, settings, semantic, catalog=None, lock_factory=worker_lock):
        self.settings = settings
        self.semantic = semantic
        self.catalog = catalog or CatalogClient(settings)
        self.lock_factory = lock_factory

    # 比较源版本与输入摘要，必要时才调用 embedding，写入前后核验 worker 所有权。
    async def _batch(self, items, check):
        documents, inputs, embedding_docs = [], [], []
        for item in items:
            document, content = document_source(item)
            document["model"] = self.settings.embedding_model
            old = await self.semantic.index.document(document["game_id"])
            if old is not None and int(old.get("version", 0)) >= document["version"]:
                self.semantic.index.validate_existing(document, old)
                continue
            if content is not None:
                if (
                    old
                    and old.get("input_text_hash") == document["input_text_hash"]
                    and old.get("model") == self.settings.embedding_model
                ):
                    document["vector"] = vector(
                        old.get("vector"), self.settings.embedding_dimensions
                    )
                else:
                    inputs.append(content)
                    embedding_docs.append(document)
            documents.append(document)
        if inputs:
            await check()
            try:
                vectors = await self.semantic.embedding.embed(inputs)
            except Exception:
                return "EMBEDDING_UNAVAILABLE"
            for document, values in zip(embedding_docs, vectors, strict=True):
                document["vector"] = values
        await check()
        for document in documents:
            await self.semantic.index.write(document)
        return None

    # 每轮领取有界任务，完整导出后写 ES，最后用同一源版本确认或记录失败。
    async def _drain(self, check, maximum):
        await check()
        await self.semantic.index.compatible()
        result = {"processed": 0, "failed": 0, "stale": 0, "busy": False}
        while result["processed"] + result["failed"] < maximum:
            await check()
            batch = await self.catalog.request(
                "pending", {"limit": min(10, maximum - result["processed"] - result["failed"])}
            )
            pending = batch.get("items")
            if not isinstance(pending, list) or len(pending) > 10:
                raise ValueError("Invalid pending catalog batch")
            if not pending:
                return result
            game_ids = [int(item["gameId"]) for item in pending]
            exported = await self.catalog.request("export", {"gameIds": game_ids})
            items = exported.get("items")
            if (
                not isinstance(items, list)
                or len(items) != len(game_ids)
                or {int(item["gameId"]) for item in items} != set(game_ids)
            ):
                raise ValueError("Incomplete public catalog export")
            acknowledgement = [
                {"gameId": int(item["gameId"]), "version": int(item["version"])} for item in items
            ]
            try:
                error = await self._batch(items, check)
            except (ValueError, KeyError, TypeError):
                error = "INDEX_PROTOCOL_ERROR"
            except Exception as exc:
                # A lost DB mutex must abort before any further embedding work or acknowledgement.
                await check()
                logger.warning("Semantic indexing failed: type=%s", type(exc).__name__)
                error = "ES_UNAVAILABLE"
            await check()
            if error:
                await self.catalog.request("failed", {"items": acknowledgement, "errorCode": error})
                result["failed"] += len(items)
            else:
                ack = await self.catalog.request("ack", {"items": acknowledgement})
                result["processed"] += len(items)
                result["stale"] += int(ack.get("stale", 0))
        return result

    async def drain(self, maximum=100, *, rebuild=False):
        if not self.settings.semantic_search_enabled or not 1 <= maximum <= 1000:
            raise ValueError("Semantic worker disabled or invalid budget")
        async with self.lock_factory(self.settings) as check:
            if check is None:
                return {"processed": 0, "failed": 0, "stale": 0, "busy": True}
            if rebuild:
                await check()
                await self.catalog.request("rebuild", {})
            return await self._drain(check, maximum)

    async def aclose(self):
        await self.catalog.aclose()


async def automatic_worker(worker):
    while True:
        try:
            await worker.drain()
        except Exception as exc:
            logger.warning("Semantic worker unavailable: type=%s", type(exc).__name__)
        await asyncio.sleep(10)


async def command(arguments):
    settings = Settings()
    semantic = SemanticSearch(settings)
    worker = CatalogWorker(settings, semantic)
    try:
        if arguments.command == "init":
            result = await semantic.index.initialize()
        elif arguments.command == "status":
            result = await worker.catalog.request("status", {})
            try:
                await semantic.index.compatible()
                result["indexCompatible"] = True
            except Exception:
                result["indexCompatible"] = False
        else:
            result = await worker.drain(arguments.maximum, rebuild=arguments.command == "rebuild")
        print(json.dumps(result, ensure_ascii=False))
    finally:
        await worker.aclose()
        await semantic.aclose()


def main():
    parser = argparse.ArgumentParser(description="Public game vector catalog worker")
    parser.add_argument("command", choices=("init", "status", "drain", "rebuild"))
    parser.add_argument("--maximum", type=int, default=100)
    arguments = parser.parse_args()
    try:
        asyncio.run(command(arguments))
    except Exception as exc:
        print(json.dumps({"error": type(exc).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
