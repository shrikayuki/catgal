# 阅读说明
# 候选提取任务的领取、完成、列表、确认与拒绝事务。
# 确认时复核用户、机器人与来源，将候选确认与长期记忆写入一起提交。

from dataclasses import dataclass, field
from datetime import timedelta

from sqlalchemy import func, select, update

from app.bots.models import Bot
from app.candidates.content import contains_credential, content_hash, source_hash
from app.candidates.models import Candidate, Extraction, SuggestionSettings
from app.long_memory.contracts import MemoryCreate
from app.long_memory.models import LongTermMemory
from app.persistence.contracts import PersistenceError
from app.persistence.models import Conversation, Message


@dataclass(frozen=True)
class ExtractionPlan:
    source_message_id: int
    user_id: int
    bot_id: int
    session_id: str
    source_hash: str
    settings_revision: int
    generation_message_id: int
    generation_token: str
    content: str = field(repr=False)


def candidate_view(row):
    return {
        "id": str(row.id),
        "botId": str(row.bot_id),
        "content": row.content,
        "memoryType": row.memory_type,
        "sourceMessageId": str(row.source_message_id),
        "sourceSessionId": row.source_session_id,
        "revision": str(row.revision),
        "createTime": row.create_time.isoformat() + "Z",
    }


class CandidateStore:
    def __init__(self, chat_store, settings):
        self.chat = chat_store
        self.sessions = chat_store.sessions
        self.config = settings

    async def health(self):
        async with self.sessions() as db:
            for model in (SuggestionSettings, Extraction, Candidate):
                await db.execute(select(model).limit(1))
        return True

    async def _bot(self, db, user_id, bot_id):
        bot = await db.scalar(
            select(Bot).where(Bot.id == bot_id, Bot.user_id == user_id).with_for_update()
        )
        if bot is None:
            raise PersistenceError("BOT_NOT_FOUND", "机器人不存在", 404)
        return bot

    async def _settings(self, db, bot_id):
        return await db.get(SuggestionSettings, bot_id, with_for_update=True)

    @staticmethod
    def _revision(row, expected):
        if (row.revision if row else 0) != expected:
            raise PersistenceError("REVISION_CONFLICT", "数据已更新，请刷新后重试", 409)

    @staticmethod
    def _settings_view(bot_id, row):
        return {
            "botId": str(bot_id),
            "enabled": bool(row.enabled) if row else False,
            "revision": str(row.revision) if row else "0",
        }

    async def settings(self, request):
        async with self.sessions.begin() as db:
            await self._bot(db, request.user_id, request.bot_id)
            return self._settings_view(request.bot_id, await self._settings(db, request.bot_id))

    async def update_settings(self, request):
        async with self.sessions.begin() as db:
            bot = await self._bot(db, request.user_id, request.bot_id)
            row = await self._settings(db, bot.id)
            self._revision(row, request.revision)
            if request.enabled and bot.status != 1:
                raise PersistenceError("BOT_NOT_FOUND", "机器人不存在", 404)
            now = await self.chat.long_term_memory._now(db)
            if row is None and request.enabled:
                row = SuggestionSettings(
                    bot_id=bot.id,
                    user_id=request.user_id,
                    enabled=False,
                    create_time=now,
                    update_time=now,
                )
                db.add(row)
                await db.flush()
            if row is not None and row.enabled != request.enabled:
                if request.enabled:
                    row.enabled_after_message_id = (
                        await db.scalar(
                            select(func.max(Message.id)).where(
                                Message.user_id == request.user_id,
                                Message.bot_id == bot.id,
                                Message.role == "user",
                            )
                        )
                        or 0
                    )
                row.enabled = request.enabled
                # First creation already starts at revision 1.
                if request.revision != 0:
                    row.revision += 1
                row.update_time = now
                if not request.enabled:
                    await db.execute(
                        update(Extraction)
                        .where(
                            Extraction.user_id == request.user_id,
                            Extraction.bot_id == bot.id,
                            Extraction.state == "running",
                        )
                        .values(
                            state="invalidated", error_code="EXTRACTION_DISABLED", update_time=now
                        )
                    )
            return self._settings_view(bot.id, row)

    async def list(self, request):
        async with self.sessions.begin() as db:
            await self._bot(db, request.user_id, request.bot_id)
            now = await self.chat.long_term_memory._now(db)
            await db.execute(
                update(Extraction)
                .where(
                    Extraction.user_id == request.user_id,
                    Extraction.bot_id == request.bot_id,
                    Extraction.state == "running",
                    Extraction.lease_until <= now,
                )
                .values(state="interrupted", error_code="EXTRACTION_INTERRUPTED", update_time=now)
            )
            query = (
                select(Candidate)
                .join(Conversation, Conversation.session_id == Candidate.source_session_id)
                .where(
                    Candidate.user_id == request.user_id,
                    Candidate.bot_id == request.bot_id,
                    Candidate.state == "pending",
                    Conversation.status == 1,
                    Conversation.user_id == request.user_id,
                    Conversation.bot_id == request.bot_id,
                )
            )
            if request.before_id is not None:
                query = query.where(Candidate.id < request.before_id)
            rows = list(
                await db.scalars(query.order_by(Candidate.id.desc()).limit(request.limit + 1))
            )
            visible = rows[: request.limit]
            latest = await db.scalar(
                select(Extraction)
                .where(Extraction.user_id == request.user_id, Extraction.bot_id == request.bot_id)
                .order_by(Extraction.update_time.desc(), Extraction.source_message_id.desc())
                .limit(1)
            )
            last = None
            if latest is not None:
                state = {"empty": "skipped", "interrupted": "failed", "invalidated": "failed"}.get(
                    latest.state, latest.state
                )
                last = {
                    "status": state,
                    "errorCode": latest.error_code,
                    "updateTime": latest.update_time.isoformat() + "Z",
                }
            return {
                "items": [candidate_view(row) for row in visible],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
                "lastExtraction": last,
            }

    async def _source(self, db, user_id, bot_id, session_id, message_id):
        return await db.scalar(
            select(Message)
            .join(Conversation, Conversation.session_id == Message.session_id)
            .where(
                Message.id == message_id,
                Message.user_id == user_id,
                Message.bot_id == bot_id,
                Message.session_id == session_id,
                Message.role == "user",
                Message.status == "completed",
                Conversation.user_id == user_id,
                Conversation.bot_id == bot_id,
                Conversation.status == 1,
            )
            .with_for_update()
        )

    async def start(self, prepared):
        if prepared.replay or prepared.assistant.regenerated_from_id is not None:
            return None
        async with self.sessions.begin() as db:
            session, _, now = await self.chat._active(db, prepared)
            if session is None:
                return None
            bot = await self._bot(db, session.user_id, prepared.profile.bot_id)
            settings = await self._settings(db, bot.id)
            if bot.status != 1 or settings is None or not settings.enabled:
                return None
            if prepared.user_message.id <= settings.enabled_after_message_id:
                return None
            job = await db.get(Extraction, prepared.user_message.id, with_for_update=True)
            if job is not None:
                return None
            source = await self._source(
                db, session.user_id, bot.id, session.session_id, prepared.user_message.id
            )
            if source is None or not source.content.strip():
                return None
            plan = ExtractionPlan(
                source.id,
                session.user_id,
                bot.id,
                session.session_id,
                source_hash(source.content),
                settings.revision,
                prepared.assistant.id,
                prepared.assistant.generation_token,
                source.content,
            )
            skipped = contains_credential(source.content) or len(source.content) > 4000
            db.add(
                Extraction(
                    source_message_id=source.id,
                    user_id=session.user_id,
                    bot_id=bot.id,
                    session_id=session.session_id,
                    source_hash=plan.source_hash,
                    settings_revision=settings.revision,
                    generation_message_id=prepared.assistant.id,
                    generation_token=prepared.assistant.generation_token,
                    lease_until=min(
                        session.active_lease_until,
                        now
                        + timedelta(seconds=self.config.candidate_extraction_timeout_seconds + 1),
                    ),
                    state="empty" if skipped else "running",
                    error_code="SENSITIVE_SOURCE_SKIPPED" if skipped else None,
                    create_time=now,
                    update_time=now,
                )
            )
            return None if skipped else plan

    async def finish(self, prepared, plan, items, *, error_code=None, interrupted=False):
        async with self.sessions.begin() as db:
            session, _, now = await self.chat._active(db, prepared)
            bot = await self._bot(db, plan.user_id, plan.bot_id)
            settings = await self._settings(db, bot.id)
            job = await db.get(Extraction, plan.source_message_id, with_for_update=True)
            if (
                job is None
                or job.state != "running"
                or job.generation_token != plan.generation_token
            ):
                return False
            source = await self._source(
                db, plan.user_id, bot.id, plan.session_id, plan.source_message_id
            )
            valid = (
                session is not None
                and bot.status == 1
                and settings is not None
                and settings.enabled
                and settings.revision == plan.settings_revision
                and job.settings_revision == plan.settings_revision
                and job.generation_message_id == prepared.assistant.id
                and job.lease_until > now
                and job.source_hash == plan.source_hash
                and source is not None
                and source_hash(source.content) == plan.source_hash
            )
            if not valid:
                job.state, job.error_code = "invalidated", "SOURCE_NOT_AVAILABLE"
            elif error_code is not None:
                job.state = "interrupted" if interrupted else "failed"
                job.error_code = error_code
            else:
                seen = set()
                for item in items:
                    digest = content_hash(item.content, item.memory_type)
                    if digest in seen or contains_credential(item.content):
                        continue
                    seen.add(digest)
                    db.add(
                        Candidate(
                            user_id=plan.user_id,
                            bot_id=bot.id,
                            source_message_id=plan.source_message_id,
                            source_session_id=plan.session_id,
                            source_hash=plan.source_hash,
                            content_hash=digest,
                            content=item.content,
                            memory_type=item.memory_type,
                            create_time=now,
                            update_time=now,
                        )
                    )
                job.state = "completed" if seen else "empty"
                job.error_code = None
            job.update_time = now
            return valid

    async def _candidate(self, db, request):
        row = await db.scalar(
            select(Candidate)
            .where(
                Candidate.id == request.candidate_id,
                Candidate.user_id == request.user_id,
                Candidate.bot_id == request.bot_id,
            )
            .with_for_update()
        )
        if row is None:
            raise PersistenceError("CANDIDATE_NOT_FOUND", "候选记忆不存在", 404)
        return row

    @staticmethod
    def _erase(row, state, now):
        row.content = ""
        row.state = state
        row.revision += 1
        row.update_time = now

    # 持锁检查候选版本和来源有效性，再把长期记忆写入与候选确认共同提交。
    async def confirm(self, request):
        failure = False
        result = None
        async with self.sessions.begin() as db:
            # Locate without a row lock, then use the common Conversation -> Bot -> Candidate order.
            session_id = await db.scalar(
                select(Candidate.source_session_id).where(
                    Candidate.id == request.candidate_id,
                    Candidate.user_id == request.user_id,
                    Candidate.bot_id == request.bot_id,
                )
            )
            if session_id is None:
                raise PersistenceError("CANDIDATE_NOT_FOUND", "候选记忆不存在", 404)
            session = await db.scalar(
                select(Conversation)
                .where(
                    Conversation.session_id == session_id, Conversation.user_id == request.user_id
                )
                .with_for_update()
            )
            bot = await self._bot(db, request.user_id, request.bot_id)
            row = await self._candidate(db, request)
            now = await self.chat.long_term_memory._now(db)
            source = await self._source(
                db, request.user_id, bot.id, session_id, row.source_message_id
            )
            if (
                session is None
                or session.status != 1
                or bot.status != 1
                or source is None
                or source_hash(source.content) != row.source_hash
            ):
                if row.state == "pending":
                    self._erase(row, "invalidated", now)
                failure = True
            else:
                self._revision(row, request.revision)
                if row.state != "pending":
                    raise PersistenceError("CANDIDATE_NOT_FOUND", "候选记忆不存在", 404)
                digest = content_hash(request.content, request.memory_type)
                existing = await db.scalars(
                    select(LongTermMemory)
                    .where(
                        LongTermMemory.user_id == request.user_id,
                        LongTermMemory.bot_id == bot.id,
                        LongTermMemory.status == 1,
                        LongTermMemory.memory_type == request.memory_type,
                    )
                    .with_for_update()
                )
                memory = next(
                    (
                        item
                        for item in existing
                        if content_hash(item.content, item.memory_type) == digest
                    ),
                    None,
                )
                duplicate = memory is not None
                if memory is None:
                    memory = await self.chat.long_term_memory.create_in_transaction(
                        db,
                        MemoryCreate(
                            user_id=request.user_id,
                            bot_id=bot.id,
                            content=request.content,
                            memory_type=request.memory_type,
                            source_message_id=row.source_message_id,
                        ),
                    )
                self._erase(row, "confirmed", now)
                row.confirmed_memory_id = memory.id
                result = {"memoryId": str(memory.id), "duplicate": duplicate}
        if failure:
            raise PersistenceError("SOURCE_NOT_AVAILABLE", "来源会话或消息已失效", 409)
        return result

    async def reject(self, request):
        async with self.sessions.begin() as db:
            await self._bot(db, request.user_id, request.bot_id)
            row = await self._candidate(db, request)
            self._revision(row, request.revision)
            if row.state != "pending":
                raise PersistenceError("CANDIDATE_NOT_FOUND", "候选记忆不存在", 404)
            self._erase(row, "rejected", await self.chat.long_term_memory._now(db))
            return {"rejected": True}

    async def invalidate(self, db, session_id):
        now = await self.chat.long_term_memory._now(db)
        await db.execute(
            update(Candidate)
            .where(Candidate.source_session_id == session_id, Candidate.state == "pending")
            .values(
                content="", state="invalidated", revision=Candidate.revision + 1, update_time=now
            )
        )
        await db.execute(
            update(Extraction)
            .where(Extraction.session_id == session_id, Extraction.state == "running")
            .values(state="invalidated", error_code="SOURCE_NOT_AVAILABLE", update_time=now)
        )
