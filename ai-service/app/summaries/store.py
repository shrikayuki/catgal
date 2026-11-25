# 阅读说明
# 摘要的准备、提交、失败和来源失效处理，源消息变化时防止旧结果覆盖。

import json
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.orm import aliased, undefer

from app.persistence.contracts import PersistenceError
from app.persistence.models import Message
from app.persistence.store import history_content
from app.summaries.models import SessionSummary


@dataclass(frozen=True)
class SummaryPlan:
    revision: int
    source_memory_version: int
    user_message_id: int
    assistant_message_id: int
    covered_turns: int
    source: str


def summary_view(session_id, row):
    return {
        "sessionId": session_id,
        "enabled": bool(row.enabled) if row else False,
        "revision": str(row.revision) if row else "0",
        "summary": row.summary if row and row.summary else None,
        "status": row.status if row else "empty",
        "coveredTurns": row.covered_turns if row else 0,
        "coveredThroughMessageId": str(row.covered_user_message_id)
        if row and row.covered_user_message_id
        else None,
        "sourceMemoryVersion": str(row.source_memory_version)
        if row and row.source_memory_version is not None
        else None,
        "lastErrorCode": row.last_error_code if row else None,
        "updateTime": row.update_time.isoformat() + "Z" if row else None,
    }


class SummaryStore:
    def __init__(self, chat_store, settings):
        self.chat = chat_store
        self.sessions = chat_store.sessions
        self.config = settings

    async def health(self):
        async with self.sessions() as db:
            await db.execute(select(SessionSummary).limit(1))
        return True

    async def _row(self, db, session_id):
        return await db.get(SessionSummary, session_id, with_for_update=True)

    @staticmethod
    def _revision(row, expected):
        if (row.revision if row else 0) != expected:
            raise PersistenceError("REVISION_CONFLICT", "摘要已更新，请刷新后重试", 409)

    async def get(self, request):
        async with self.sessions.begin() as db:
            session = await self.chat._owned(db, request)
            return summary_view(session.session_id, await self._row(db, session.session_id))

    async def settings(self, request):
        async with self.sessions.begin() as db:
            session = await self.chat._owned(db, request)
            row = await self._row(db, session.session_id)
            self._revision(row, request.revision)
            if request.enabled:
                if self.chat.bots is None or session.bot_id is None:
                    raise PersistenceError("SUMMARY_UNAVAILABLE", "会话机器人配置不可用", 503)
                await self.chat.bots.owned(db, request.user_id, session.bot_id)
            now = await self.chat._now(db)
            if row is None and request.enabled:
                row = SessionSummary(
                    session_id=session.session_id,
                    user_id=request.user_id,
                    bot_id=session.bot_id,
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
            return summary_view(session.session_id, row)

    async def clear(self, request):
        async with self.sessions.begin() as db:
            session = await self.chat._owned(db, request)
            row = await self._row(db, session.session_id)
            self._revision(row, request.revision)
            if row is not None:
                self._reset(row, await self.chat._now(db), disable=True)
            return summary_view(session.session_id, row)

    @staticmethod
    def _reset(row, now, *, disable=False):
        row.summary = ""
        row.covered_user_message_id = None
        row.covered_assistant_message_id = None
        row.covered_turns = 0
        row.source_memory_version = None
        row.last_error_code = None
        row.status = "empty"
        row.revision += 1
        row.update_time = now
        if disable:
            row.enabled = False

    async def invalidate(self, db, session_id, user_message_id=None):
        row = await self._row(db, session_id)
        if row is not None and (
            user_message_id is None
            or (
                row.covered_user_message_id is not None
                and user_message_id <= row.covered_user_message_id
            )
        ):
            self._reset(row, await self.chat._now(db), disable=user_message_id is None)

    # 持久化摘要生成计划及来源快照，离开短事务后再执行外部模型调用。
    async def prepare(self, prepared):
        async with self.sessions.begin() as db:
            session, _, now = await self.chat._active(db, prepared)
            if session is None:
                return "", None
            row = await self._row(db, session.session_id)
            if row is None or not row.enabled:
                return "", None
            if row.user_id != session.user_id or row.bot_id != prepared.profile.bot_id:
                raise PersistenceError("SUMMARY_UNAVAILABLE", "摘要归属异常", 503)
            completed = (
                select(
                    func.max(Message.id).label("assistant_id"), Message.reply_to_id.label("user_id")
                )
                .where(
                    Message.session_id == session.session_id,
                    Message.user_id == session.user_id,
                    Message.role == "assistant",
                    Message.status == "completed",
                    Message.reply_to_id < prepared.user_message.id,
                )
                .group_by(Message.reply_to_id)
                .subquery()
            )
            if row.covered_user_message_id is not None:
                anchor = await db.scalar(
                    select(completed.c.assistant_id).where(
                        completed.c.user_id == row.covered_user_message_id
                    )
                )
                count = await db.scalar(
                    select(func.count())
                    .select_from(completed)
                    .where(completed.c.user_id <= row.covered_user_message_id)
                )
                if anchor != row.covered_assistant_message_id or count != row.covered_turns:
                    self._reset(row, now)
            cutoff = await db.scalar(
                select(completed.c.user_id).order_by(completed.c.user_id.desc()).offset(10).limit(1)
            )
            if cutoff is None:
                return row.summary, None
            user = aliased(Message)
            query = (
                select(Message, user.content)
                .join(completed, Message.id == completed.c.assistant_id)
                .join(user, user.id == completed.c.user_id)
                .where(
                    completed.c.user_id <= cutoff,
                    user.status == "completed",
                    user.role == "user",
                    user.user_id == session.user_id,
                    user.session_id == session.session_id,
                )
            )
            if row.covered_user_message_id is not None:
                query = query.where(completed.c.user_id > row.covered_user_message_id)
            if self.chat.game_tools:
                query = query.options(undefer(Message.recommendations))
            pairs = await db.execute(
                query.order_by(completed.c.user_id).limit(self.config.summary_batch_turns)
            )
            selected = []
            last = None
            previous = row.summary
            for assistant, user_content in pairs:
                turn = {"user": user_content, "assistant": history_content(assistant)}
                encoded = json.dumps(
                    {"previousSummary": previous, "olderTurns": [*selected, turn]},
                    ensure_ascii=False,
                )
                if len(encoded) > self.config.summary_source_chars:
                    if selected:
                        break
                    turn["truncated"] = True
                    while (
                        len(
                            json.dumps(
                                {"previousSummary": previous, "olderTurns": [turn]},
                                ensure_ascii=False,
                            )
                        )
                        > self.config.summary_source_chars
                    ):
                        field = max(("user", "assistant"), key=lambda name: len(turn[name]))
                        if len(previous) > len(turn[field]):
                            previous = previous[: len(previous) // 2]
                        else:
                            turn[field] = turn[field][: len(turn[field]) // 2]
                selected.append(turn)
                last = assistant
            if last is None:
                return row.summary, None
            source = json.dumps(
                {"previousSummary": previous, "olderTurns": selected}, ensure_ascii=False
            )
            plan = SummaryPlan(
                row.revision,
                session.memory_version,
                last.reply_to_id,
                last.id,
                row.covered_turns + len(selected),
                source,
            )
            return row.summary, plan

    # 核验生成计划、会话和来源版本，拒绝过期模型结果覆盖当前摘要。
    async def commit(self, prepared, plan, summary):
        async with self.sessions.begin() as db:
            session, _, now = await self.chat._active(db, prepared)
            if session is None or session.memory_version != plan.source_memory_version:
                return False
            row = await self._row(db, session.session_id)
            if row is None or not row.enabled or row.revision != plan.revision:
                return False
            row.summary = summary
            row.covered_user_message_id = plan.user_message_id
            row.covered_assistant_message_id = plan.assistant_message_id
            row.covered_turns = plan.covered_turns
            row.source_memory_version = plan.source_memory_version
            row.status = "ready"
            row.last_error_code = None
            row.revision += 1
            row.update_time = now
            return True

    async def failed(self, prepared, plan, code):
        async with self.sessions.begin() as db:
            session, _, now = await self.chat._active(db, prepared)
            if session is None or session.memory_version != plan.source_memory_version:
                return
            row = await self._row(db, session.session_id)
            if row is not None and row.enabled and row.revision == plan.revision:
                row.status = "failed"
                row.last_error_code = code
                row.revision += 1
                row.update_time = now
