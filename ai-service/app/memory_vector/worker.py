# 阅读说明
# 私有记忆向量增量同步，按当前授权和源版本写入有效或删除标记文档并确认任务。

import argparse
import asyncio
import json
import logging

from app.config import Settings
from app.long_memory.store import LongTermMemoryStore
from app.memory_vector.index import PrivateMemoryIndex
from app.memory_vector.service import MemoryVectorService
from app.memory_vector.store import MemoryVectorStore
from app.persistence.database import create_database, session_factory
from app.persistence.store import ChatStore
from app.semantic.client import EmbeddingClient, vector
from app.semantic.worker import worker_lock

logger = logging.getLogger(__name__)


class MemoryVectorWorker:
    def __init__(self, store, service, lock_factory=worker_lock):
        self.store = store
        self.service = service
        self.lock_factory = lock_factory
        self.lock_settings = store.config.model_copy(
            update={"semantic_index_name": store.config.memory_vector_index_name}
        )

    async def _batch(self, sources, check):
        prepared, inputs, positions = [], [], []
        for source in sources:
            await check()
            current = await self.store.source(source.memory_id)
            document = current.document(self.store.config.embedding_model)
            previous = await self.service.index.document(current.memory_id)
            if previous and int(previous.get("version", 0)) >= current.version:
                self.service.index.validate_existing(document, previous)
                prepared.append((current, document, False))
                continue
            prepared.append((current, document, True))
            if current.content is not None:
                if (
                    previous
                    and previous.get("input_text_hash") == document["input_text_hash"]
                    and previous.get("model") == self.store.config.embedding_model
                ):
                    document["vector"] = vector(
                        previous.get("vector"), self.store.config.embedding_dimensions
                    )
                else:
                    positions.append(len(prepared) - 1)
                    inputs.append(current.content)
        if inputs:
            await check()
            for position in positions:
                source, document, _ = prepared[position]
                current = await self.store.source(source.memory_id)
                if current.document(self.store.config.embedding_model) != document:
                    return None, len(sources)
            try:
                vectors = await self.service.embedding.embed(inputs)
            except Exception:
                return "EMBEDDING_UNAVAILABLE", 0
            for position, values in zip(positions, vectors, strict=True):
                prepared[position][1]["vector"] = values
        stale = 0
        for source, document, write in prepared:
            await check()
            latest = await self.store.source(source.memory_id)
            latest_document = latest.document(self.store.config.embedding_model)
            if latest_document != {
                key: value for key, value in document.items() if key != "vector"
            }:
                if latest.content is not None:
                    stale += 1
                    continue
                source, document, write = latest, latest_document, True
            if write:
                await self.service.index.write(document)
            acknowledged = await self.store.acknowledge(source)
            if not acknowledged:
                stale += 1
        return None, stale

    async def drain(self, maximum=100, *, rebuild=False):
        if not 1 <= maximum <= 100:
            raise ValueError("Private worker batch budget exceeded")
        async with self.lock_factory(self.lock_settings) as check:
            if check is None:
                return {"processed": 0, "failed": 0, "stale": 0, "busy": True}
            await check()
            await self.service.index.compatible()
            if rebuild:
                await self.store.rebuild()
            result = {"processed": 0, "failed": 0, "stale": 0, "busy": False}
            while result["processed"] + result["failed"] < maximum:
                await check()
                ids = await self.store.pending(
                    min(10, maximum - result["processed"] - result["failed"])
                )
                if not ids:
                    return result
                sources = [await self.store.source(memory_id) for memory_id in ids]
                try:
                    error, stale = await self._batch(sources, check)
                except (ValueError, KeyError, TypeError):
                    error, stale = "INDEX_PROTOCOL_ERROR", 0
                except Exception as exc:
                    await check()
                    logger.warning(
                        "Private vector indexing unavailable: type=%s", type(exc).__name__
                    )
                    error, stale = "ES_UNAVAILABLE", 0
                await check()
                if error:
                    for source in sources:
                        await self.store.failed(source, error)
                    result["failed"] += len(sources)
                else:
                    result["processed"] += len(sources)
                    result["stale"] += stale
            return result


async def automatic_memory_worker(worker):
    while True:
        try:
            await worker.drain()
        except Exception as exc:
            logger.warning("Private vector worker unavailable: type=%s", type(exc).__name__)
        await asyncio.sleep(10)


async def command(arguments):
    settings = Settings()
    engine = create_database(settings)
    sessions = session_factory(engine)
    chat = ChatStore(sessions, settings.chat_model, long_term_memory=LongTermMemoryStore(sessions))
    store = MemoryVectorStore(chat, settings)
    embedding = EmbeddingClient(settings)
    service = MemoryVectorService(store, embedding, PrivateMemoryIndex(settings))
    worker = MemoryVectorWorker(store, service)
    try:
        if arguments.command == "init":
            result = await service.index.initialize()
        elif arguments.command == "status":
            result = await store.status()
            try:
                await service.index.compatible()
                result["indexCompatible"] = True
            except Exception:
                result["indexCompatible"] = False
        else:
            result = await worker.drain(arguments.maximum, rebuild=arguments.command == "rebuild")
        print(json.dumps(result))
    finally:
        await service.aclose()
        await embedding.aclose()
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description="Private confirmed-memory vector worker")
    parser.add_argument("command", choices=("init", "status", "drain", "rebuild"))
    parser.add_argument("--maximum", type=int, default=100)
    args = parser.parse_args()
    try:
        asyncio.run(command(args))
    except Exception as exc:
        print(json.dumps({"error": type(exc).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
