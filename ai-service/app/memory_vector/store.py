# 阅读说明
# 私有记忆源、授权版本、待同步任务和命中回库复核。
# 向量文档是派生状态，当前授权、归属、删除和正文版本由数据库确认。

from dataclasses import dataclass, field
from datetime import timedelta

from sqlalchemy import func, select, update
from sqlalchemy.orm import undefer

from app.bots.models import Bot
from app.candidates.content import contains_credential
from app.long_memory.models import LongTermMemory
from app.memory_vector.models import MemoryVectorOutbox, MemoryVectorSettings
from app.persistence.contracts import PersistenceError
from app.semantic.source import digest


@dataclass(frozen=True)
class MemorySource:
    memory_id: int
    user_id: int
    bot_id: int
    version: int
    source_revision: int
    consent_revision: int
    content: str | None = field(repr=False)

    def document(self, model):
        fingerprint = digest(self.content) if self.content is not None else None
        return {
            "memory_id": self.memory_id,
            "user_id": self.user_id,
            "bot_id": self.bot_id,
            "version": self.version,
            "source_revision": self.source_revision,
            "consent_revision": self.consent_revision,
            "deleted": self.content is None,
            "source_text_hash": fingerprint,
            "input_text_hash": fingerprint,
            "model": model,
        }


class MemoryVectorStore:
    def __init__(self, chat_store, settings):
        self.chat = chat_store
        self.sessions = chat_store.sessions
        self.memory = chat_store.long_term_memory
        self.config = settings

    async def health(self):
        async with self.sessions() as db:
            await db.execute(select(MemoryVectorSettings).limit(1))
            await db.execute(select(MemoryVectorOutbox).limit(1))
        return True

    @staticmethod
    def _view(bot_id, row):
        return {
            "botId": str(bot_id),
            "enabled": bool(row.enabled) if row else False,
            "revision": str(row.revision) if row else "0",
        }

    async def settings(self, request):
        async with self.sessions.begin() as db:
            await self.memory._bot(db, request.user_id, request.bot_id)
            row = await db.get(MemoryVectorSettings, request.bot_id, with_for_update=True)
            return self._view(request.bot_id, row)

    async def update_settings(self, request):
        async with self.sessions.begin() as db:
            bot = await self.memory._bot(db, request.user_id, request.bot_id)
            row = await db.get(MemoryVectorSettings, bot.id, with_for_update=True)
            if (row.revision if row else 0) != request.revision:
                raise PersistenceError("REVISION_CONFLICT", "授权已更新，请刷新后重试", 409)
            if request.enabled and bot.status != 1:
                raise PersistenceError("BOT_NOT_FOUND", "机器人不存在", 404)
            if request.enabled and not bot.memory_enabled:
                raise PersistenceError("MEMORY_VECTOR_REQUIRES_MEMORY", "请先启用长期记忆", 409)
            now = await self.memory._now(db)
            if row is None and request.enabled:
                row = MemoryVectorSettings(
                    bot_id=bot.id,
                    user_id=request.user_id,
                    enabled=True,
                    create_time=now,
                    update_time=now,
                )
                db.add(row)
                await db.flush()
            elif row is not None and row.enabled != request.enabled:
                row.enabled = request.enabled
                row.revision += 1
                row.update_time = now
            return self._view(bot.id, row)

    async def _consent(self, db, user_id, bot_id):
        bot = await self.memory._bot(db, user_id, bot_id)
        grant = await db.get(MemoryVectorSettings, bot_id, with_for_update=True)
        if (
            bot.status != 1
            or not bot.memory_enabled
            or grant is None
            or not grant.enabled
            or grant.user_id != user_id
        ):
            return None
        return grant.revision

    async def consent(self, user_id, bot_id):
        async with self.sessions.begin() as db:
            return await self._consent(db, user_id, bot_id)

    async def pending(self, limit):
        async with self.sessions() as db:
            now = await self.memory._now(db)
            return list(
                await db.scalars(
                    select(MemoryVectorOutbox.memory_id)
                    .where(
                        MemoryVectorOutbox.pending.is_(True),
                        MemoryVectorOutbox.next_attempt_at <= now,
                    )
                    .order_by(MemoryVectorOutbox.next_attempt_at, MemoryVectorOutbox.memory_id)
                    .limit(limit)
                )
            )

    async def source(self, memory_id):
        async with self.sessions.begin() as db:
            location = (
                await db.execute(
                    select(MemoryVectorOutbox.user_id, MemoryVectorOutbox.bot_id).where(
                        MemoryVectorOutbox.memory_id == memory_id
                    )
                )
            ).first()
            if location is None:
                raise ValueError("Missing private vector event")
            bot = await db.scalar(
                select(Bot)
                .options(undefer(Bot.memory_enabled))
                .where(Bot.id == location.bot_id)
                .with_for_update()
            )
            grant = await db.get(MemoryVectorSettings, location.bot_id, with_for_update=True)
            memory = await db.get(LongTermMemory, memory_id, with_for_update=True)
            event = await db.get(MemoryVectorOutbox, memory_id, with_for_update=True)
            if event.user_id != location.user_id or event.bot_id != location.bot_id:
                raise ValueError("Private vector owner changed")
            active = (
                self.config.memory_vector_enabled
                and memory is not None
                and memory.status == 1
                and bot is not None
                and bot.status == 1
                and bot.memory_enabled
                and memory.user_id == bot.user_id == event.user_id
                and memory.bot_id == bot.id == event.bot_id
                and grant is not None
                and grant.enabled
                and grant.user_id == event.user_id
                and not contains_credential(memory.content)
            )
            content = memory.memory_type + "\n" + memory.content if active else None
            return MemorySource(
                memory_id,
                event.user_id,
                event.bot_id,
                event.version,
                memory.revision if memory else 0,
                grant.revision if grant else 0,
                content,
            )

    # 候选必须匹配当前用户、机器人、授权和内容版本，并从数据库读取有效正文。
    async def hydrate(self, user_id, bot_id, consent_revision, candidates):
        async with self.sessions.begin() as db:
            if await self._consent(db, user_id, bot_id) != consent_revision:
                return None
            selected = []
            for item in candidates[:50]:
                if (
                    int(item["user_id"]) != user_id
                    or int(item["bot_id"]) != bot_id
                    or int(item["consent_revision"]) != consent_revision
                ):
                    continue
                memory_id = int(item["memory_id"])
                if 0 < memory_id < 2**63:
                    selected.append((memory_id, item))
            ids = sorted({memory_id for memory_id, _ in selected})
            memories = {
                row.id: row
                for row in await db.scalars(
                    select(LongTermMemory)
                    .where(
                        LongTermMemory.id.in_(ids),
                        LongTermMemory.user_id == user_id,
                        LongTermMemory.bot_id == bot_id,
                        LongTermMemory.status == 1,
                    )
                    .order_by(LongTermMemory.id)
                    .with_for_update()
                )
            }
            events = {
                row.memory_id: row
                for row in await db.scalars(
                    select(MemoryVectorOutbox)
                    .where(MemoryVectorOutbox.memory_id.in_(ids))
                    .order_by(MemoryVectorOutbox.memory_id)
                    .with_for_update()
                )
            }
            facts, seen, remaining = [], set(), 5000
            for memory_id, hit in selected:
                memory, event = memories.get(memory_id), events.get(memory_id)
                if (
                    memory is None
                    or event is None
                    or memory_id in seen
                    or event.user_id != user_id
                    or event.bot_id != bot_id
                    or event.version != int(hit["version"])
                    or memory.revision != int(hit["source_revision"])
                    or contains_credential(memory.content)
                    or digest(memory.memory_type + "\n" + memory.content) != hit["source_text_hash"]
                ):
                    continue
                content = memory.content[:remaining]
                if not content:
                    break
                facts.append({"type": memory.memory_type, "content": content})
                remaining -= len(content)
                seen.add(memory_id)
                if len(facts) == 10:
                    break
            return facts or None

    async def acknowledge(self, source):
        async with self.sessions.begin() as db:
            now = await self.memory._now(db)
            result = await db.execute(
                update(MemoryVectorOutbox)
                .where(
                    MemoryVectorOutbox.memory_id == source.memory_id,
                    MemoryVectorOutbox.version == source.version,
                )
                .values(pending=False, attempts=0, last_error=None, update_time=now)
            )
            return result.rowcount == 1

    async def failed(self, source, code):
        async with self.sessions.begin() as db:
            row = await db.get(MemoryVectorOutbox, source.memory_id, with_for_update=True)
            if row is not None and row.version == source.version:
                row.attempts = min(row.attempts + 1, 2**31 - 1)
                row.last_error = code
                row.update_time = await self.memory._now(db)
                row.next_attempt_at = row.update_time + timedelta(
                    seconds=min(300, 2 ** min(row.attempts, 8))
                )

    async def status(self):
        async with self.sessions() as db:
            return {
                "pending": await db.scalar(
                    select(func.count())
                    .select_from(MemoryVectorOutbox)
                    .where(MemoryVectorOutbox.pending.is_(True))
                ),
                "failed": await db.scalar(
                    select(func.count())
                    .select_from(MemoryVectorOutbox)
                    .where(MemoryVectorOutbox.pending.is_(True), MemoryVectorOutbox.attempts > 0)
                ),
            }

    async def rebuild(self):
        async with self.sessions.begin() as db:
            now = await self.memory._now(db)
            result = await db.execute(
                update(MemoryVectorOutbox).values(
                    version=MemoryVectorOutbox.version + 1,
                    pending=True,
                    attempts=0,
                    next_attempt_at=now,
                    dirty_since=now,
                    update_time=now,
                    last_error=None,
                )
            )
            return result.rowcount
