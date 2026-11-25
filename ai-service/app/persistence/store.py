# 阅读说明
# 会话消息事务和幂等状态机，用户会话归属、消息租约及生成版本由这里核验。
# 模型调用在短事务之间执行，检查点与终态提交均复核当前生成资格。

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from uuid import uuid4

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import undefer

from app.persistence.contracts import (
    MessageQuery,
    PersistenceError,
    PersistentChatRequest,
    SessionCreate,
    SessionQuery,
    SessionRequest,
)
from app.persistence.models import Character, Conversation, Message


@dataclass
class PreparedGeneration:
    conversation: Conversation
    assistant: Message
    user_message: Message
    system_prompt: str
    replay: bool
    profile: object | None = field(default=None, repr=False)


def history_content(message: Message) -> str:
    content = message.content
    cards = message.__dict__.get("recommendations")
    if cards:
        content += "\n本站候选作品 " + json.dumps(cards, ensure_ascii=False)
    return content


def session_view(row: Conversation) -> dict:
    return {
        "id": str(row.id),
        "sessionId": row.session_id,
        "girlCode": row.girl_code,
        "botId": str(row.__dict__["bot_id"]) if row.__dict__.get("bot_id") else None,
        "title": row.title,
        "messageCount": row.message_count,
        "memoryVersion": str(row.memory_version),
        "activeMessageId": str(row.active_message_id) if row.active_message_id else None,
        "createTime": row.create_time.isoformat() + "Z",
        "updateTime": row.update_time.isoformat() + "Z",
    }


def message_view(row: Message) -> dict:
    return {
        "id": str(row.id),
        "role": row.role,
        "content": row.content,
        "status": row.status,
        "replyToId": str(row.reply_to_id) if row.reply_to_id else None,
        "regeneratedFromId": str(row.regenerated_from_id) if row.regenerated_from_id else None,
        "clientMessageId": row.client_message_id,
        "requestId": row.request_id,
        "model": row.model,
        "botId": str(row.__dict__["bot_id"]) if row.__dict__.get("bot_id") else None,
        "promptVersionId": str(row.__dict__["prompt_version_id"])
        if row.__dict__.get("prompt_version_id")
        else None,
        "providerCode": row.__dict__.get("provider_code"),
        "recommendations": row.__dict__.get("recommendations") or [],
        "errorCode": row.error_code,
        "createTime": row.create_time.isoformat() + "Z",
        "finishedTime": row.finished_time.isoformat() + "Z" if row.finished_time else None,
    }


class ChatStore:
    def __init__(
        self,
        sessions,
        model: str,
        lease_seconds: float = 105,
        bots=None,
        game_tools=False,
        long_term_memory=None,
        summaries=None,
        candidates=None,
        memory_vectors=None,
    ):
        self.sessions = sessions
        self.model = model
        self.lease_seconds = lease_seconds
        self.bots = bots
        self.game_tools = game_tools
        self.long_term_memory = long_term_memory
        self.summaries = summaries
        self.candidates = candidates
        self.memory_vectors = memory_vectors

    async def health(self) -> bool:
        async with self.sessions() as db:
            if self.game_tools:
                await db.execute(select(Message.recommendations).limit(1))
            await db.execute(
                select(
                    Conversation.title,
                    Conversation.memory_version,
                    Conversation.active_message_id,
                    Conversation.active_lease_until,
                ).limit(1)
            )
            await db.execute(
                select(Message.request_hash, Message.reply_to_id, Message.status).limit(1)
            )
        return True

    async def _now(self, db) -> datetime:
        return await db.scalar(select(func.current_timestamp()))

    async def _owned(self, db, request: SessionRequest) -> Conversation:
        row = await db.scalar(
            select(Conversation)
            .where(
                Conversation.session_id == request.session_id,
                Conversation.user_id == request.user_id,
            )
            .options(*([undefer(Conversation.bot_id)] if self.bots else []))
            .with_for_update()
        )
        if (
            row is None
            or row.status != 1
            or (request.girl_code is not None and row.girl_code != request.girl_code)
        ):
            raise PersistenceError("SESSION_NOT_FOUND", "会话不存在", 404)
        return row

    async def _recover_expired(self, db, row: Conversation, now: datetime):
        if row.active_message_id is None or (
            row.active_lease_until is not None and row.active_lease_until > now
        ):
            return
        message = await db.get(Message, row.active_message_id)
        if message is not None:
            if message.session_id != row.session_id or message.user_id != row.user_id:
                raise PersistenceError("PERSISTENCE_UNAVAILABLE", "会话状态异常", 503)
            if message.status == "running":
                message.status = "interrupted"
                message.error_code = "GENERATION_INTERRUPTED"
                message.finished_time = now
                message.update_time = now
        row.active_message_id = None
        row.active_lease_until = None

    async def create_session(self, request: SessionCreate) -> dict:
        async with self.sessions.begin() as db:
            bot = None
            if request.bot_id is not None:
                if self.bots is None:
                    raise PersistenceError(
                        "PERSONALIZATION_UNAVAILABLE", "个人机器人功能未启用", 503
                    )
                bot = await self.bots.owned(db, request.user_id, request.bot_id)
                character = await self.bots.template(db, template_id=bot.template_id)
            else:
                character = await db.scalar(
                    select(Character).where(
                        Character.girl_code == request.girl_code,
                        Character.status == 1,
                    )
                )
            if character is None:
                raise PersistenceError("CHARACTER_UNAVAILABLE", "角色不存在或未启用", 404)
            now = await self._now(db)
            row = Conversation(
                user_id=request.user_id,
                girl_id=character.id,
                girl_code=character.girl_code,
                session_id=str(uuid4()),
                title=request.title,
                create_time=now,
                update_time=now,
            )
            db.add(row)
            if self.bots is not None:
                bot = bot or await self.bots.ensure_platform(db, request.user_id, character)
                row.bot_id = bot.id
            await db.flush()
            return session_view(row)

    async def list_sessions(self, request: SessionQuery) -> dict:
        async with self.sessions() as db:
            query = select(Conversation).where(
                Conversation.user_id == request.user_id,
                Conversation.status == 1,
            )
            if self.bots is not None:
                query = query.options(undefer(Conversation.bot_id))
            if request.bot_id is not None:
                if self.bots is None:
                    raise PersistenceError(
                        "PERSONALIZATION_UNAVAILABLE", "个人机器人功能未启用", 503
                    )
                query = query.where(Conversation.bot_id == request.bot_id)
            if request.girl_code is not None:
                query = query.where(Conversation.girl_code == request.girl_code)
            if request.before_id is not None:
                query = query.where(Conversation.id < request.before_id)
            rows = list(
                await db.scalars(query.order_by(Conversation.id.desc()).limit(request.limit + 1))
            )
            visible = rows[: request.limit]
            return {
                "items": [session_view(row) for row in visible],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
            }

    async def get_session(self, request: SessionRequest) -> dict:
        async with self.sessions.begin() as db:
            row = await self._owned(db, request)
            await self._recover_expired(db, row, await self._now(db))
            return session_view(row)

    async def list_messages(self, request: MessageQuery) -> dict:
        async with self.sessions.begin() as db:
            row = await self._owned(db, request)
            await self._recover_expired(db, row, await self._now(db))
            await db.flush()
            query = select(Message).where(Message.session_id == row.session_id)
            if self.game_tools:
                query = query.options(undefer(Message.recommendations))
            if self.bots is not None:
                query = query.options(
                    undefer(Message.bot_id),
                    undefer(Message.prompt_version_id),
                    undefer(Message.provider_code),
                )
            if request.before_id is not None:
                query = query.where(Message.id < request.before_id)
            rows = list(
                await db.scalars(query.order_by(Message.id.desc()).limit(request.limit + 1))
            )
            visible = rows[: request.limit]
            return {
                "items": [message_view(message) for message in reversed(visible)],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
            }

    # 会话软删除时中断活动消息并清理摘要候选资格，随后到达的生成结果会失去提交资格。
    async def delete_session(self, request: SessionRequest) -> dict:
        async with self.sessions.begin() as db:
            row = await db.scalar(
                select(Conversation)
                .where(
                    Conversation.session_id == request.session_id,
                    Conversation.user_id == request.user_id,
                )
                .with_for_update()
            )
            if row is None:
                raise PersistenceError("SESSION_NOT_FOUND", "会话不存在", 404)
            if row.status == 0:
                return {"deleted": True}
            now = await self._now(db)
            if row.active_message_id is not None:
                active = await db.get(Message, row.active_message_id)
                if (
                    active is not None
                    and active.session_id == row.session_id
                    and active.status == "running"
                ):
                    active.status = "interrupted"
                    active.error_code = "SESSION_DELETED"
                    active.update_time = now
                    active.finished_time = now
            row.status = 0
            if self.summaries is not None:
                await self.summaries.invalidate(db, row.session_id)
            if self.candidates is not None:
                await self.candidates.invalidate(db, row.session_id)
            row.active_message_id = None
            row.active_lease_until = None
            row.memory_version += 1
            row.update_time = now
            return {"deleted": True}

    # 请求正文与影响生成的选项计算指纹，用户级 client_message_id 用于识别重试和冲突。
    async def begin(self, request: PersistentChatRequest) -> PreparedGeneration:
        identity = {
            "session": request.session_id,
            "content": request.content,
            "regenerate": request.regenerate_message_id,
        }
        # Preserve pre-B request hashes for clients replaying existing messages.
        if request.use_interests:
            identity["useInterests"] = True
        if request.use_game_tools:
            identity["useGameTools"] = True
        if request.use_semantic_search:
            identity["useSemanticSearch"] = True
        fingerprint = hashlib.sha256(
            json.dumps(
                identity,
                sort_keys=True,
                ensure_ascii=False,
            ).encode()
        ).hexdigest()
        try:
            return await self._begin(request, fingerprint)
        except IntegrityError:
            # A user can race the same globally unique key in two different sessions.
            async with self.sessions() as db:
                duplicate = await db.scalar(
                    select(Message).where(
                        Message.user_id == request.user_id,
                        Message.client_message_id == request.client_message_id,
                    )
                )
                if duplicate is None:
                    raise
            raise PersistenceError("IDEMPOTENCY_CONFLICT", "消息幂等键已被其他请求使用") from None

    # 会话行锁内恢复过期租约、检查幂等，再创建 running 回答并绑定生成 token。
    async def _begin(self, request, fingerprint):
        async with self.sessions.begin() as db:
            row = await self._owned(db, request)
            now = await self._now(db)
            await self._recover_expired(db, row, now)
            await db.flush()
            previous = await db.scalar(
                select(Message)
                .options(*([undefer(Message.recommendations)] if self.game_tools else []))
                .where(
                    Message.user_id == request.user_id,
                    Message.client_message_id == request.client_message_id,
                )
            )
            if previous is not None:
                if previous.request_hash != fingerprint or previous.session_id != row.session_id:
                    raise PersistenceError("IDEMPOTENCY_CONFLICT", "消息幂等键与原请求不一致")
                if previous.status == "running":
                    raise PersistenceError("REQUEST_IN_PROGRESS", "此消息正在生成，请读取历史状态")
                user_message = await db.get(Message, previous.reply_to_id)
                return PreparedGeneration(row, previous, user_message, "", True)
            if row.active_message_id is not None:
                raise PersistenceError("CONVERSATION_BUSY", "当前会话正在生成")
            if request.use_game_tools and not self.game_tools:
                raise PersistenceError("GAME_TOOLS_UNAVAILABLE", "游戏查询服务未启用", 503)
            character = await db.get(Character, row.girl_id)
            if character is None or character.status != 1:
                raise PersistenceError("CHARACTER_UNAVAILABLE", "角色不存在或未启用", 404)
            if not character.system_prompt or len(character.system_prompt) > 8000:
                raise PersistenceError("CHARACTER_UNAVAILABLE", "角色提示词配置不可用", 503)
            profile = await self.bots.resolve(db, row) if self.bots is not None else None
            if request.regenerate_message_id is not None:
                target = await db.get(Message, request.regenerate_message_id)
                if (
                    target is None
                    or target.session_id != row.session_id
                    or target.role != "assistant"
                ):
                    raise PersistenceError("MESSAGE_NOT_FOUND", "回答不存在", 404)
                latest_user = await db.scalar(
                    select(func.max(Message.id)).where(
                        Message.session_id == row.session_id,
                        Message.role == "user",
                    )
                )
                if target.reply_to_id != latest_user:
                    raise PersistenceError("REGENERATION_CONFLICT", "只允许重新生成最近一轮回答")
                user_message = await db.get(Message, target.reply_to_id)
                if self.summaries is not None:
                    await self.summaries.invalidate(db, row.session_id, user_message.id)
            else:
                user_message = Message(
                    session_id=row.session_id,
                    user_id=row.user_id,
                    role="user",
                    content=request.content,
                    status="completed",
                    create_time=now,
                    update_time=now,
                    finished_time=now,
                )
                db.add(user_message)
                await db.flush()
            assistant = Message(
                session_id=row.session_id,
                user_id=row.user_id,
                role="assistant",
                content="",
                status="running",
                reply_to_id=user_message.id,
                regenerated_from_id=request.regenerate_message_id,
                client_message_id=request.client_message_id,
                request_id=request.request_id,
                request_hash=fingerprint,
                generation_token=uuid4().hex,
                model=profile.model if profile else self.model,
                prompt_hash=hashlib.sha256(
                    (profile.system_prompt if profile else character.system_prompt).encode()
                ).hexdigest(),
                create_time=now,
                update_time=now,
            )
            if profile is not None:
                assistant.bot_id = profile.bot_id
                assistant.prompt_version_id = profile.prompt_version_id
                assistant.credential_id = profile.credential_id
                assistant.credential_revision = profile.credential_revision
                assistant.provider_code = profile.provider_code
                if request.regenerate_message_id is None:
                    user_message.bot_id = profile.bot_id
            db.add(assistant)
            await db.flush()
            row.active_message_id = assistant.id
            row.active_lease_until = now + timedelta(seconds=self.lease_seconds)
            row.update_time = now
            return PreparedGeneration(
                row,
                assistant,
                user_message,
                profile.system_prompt if profile else character.system_prompt,
                False,
                profile,
            )

    # 检查会话有效性、租约、活动消息和 generation_token，只有当前生成可以写检查点或终态。
    async def _active(self, db, prepared: PreparedGeneration):
        row = await db.scalar(
            select(Conversation)
            .where(
                Conversation.session_id == prepared.conversation.session_id,
                Conversation.user_id == prepared.conversation.user_id,
            )
            .with_for_update()
        )
        now = await self._now(db)
        if (
            row is None
            or row.status != 1
            or row.active_message_id != prepared.assistant.id
            or row.active_lease_until is None
            or row.active_lease_until <= now
        ):
            return None, None, now
        message = await db.get(Message, prepared.assistant.id)
        if (
            message is None
            or message.status != "running"
            or message.generation_token != prepared.assistant.generation_token
        ):
            return None, None, now
        return row, message, now

    # 短事务复核生成资格后保存已生成文本，用于断流后的状态读取。
    async def checkpoint(self, prepared, content):
        async with self.sessions.begin() as db:
            row, message, now = await self._active(db, prepared)
            if row is None:
                raise PersistenceError("GENERATION_INTERRUPTED", "此生成请求已失效")
            message.content = content
            message.update_time = now

    # 提交回答终态并解除活动消息资格，记忆版本随完成结果推进。
    async def finish(
        self, prepared, content, status="completed", error_code=None, recommendations=None
    ) -> int | None:
        async with self.sessions.begin() as db:
            row, message, now = await self._active(db, prepared)
            if row is None:
                return None
            already_answered = await db.scalar(
                select(Message.id)
                .where(
                    Message.session_id == row.session_id,
                    Message.reply_to_id == message.reply_to_id,
                    Message.role == "assistant",
                    Message.status == "completed",
                )
                .limit(1)
            )
            message.content = content
            message.status = status
            message.error_code = error_code
            message.update_time = now
            message.finished_time = now
            if self.game_tools and recommendations:
                message.recommendations = recommendations
            row.active_message_id = None
            row.active_lease_until = None
            row.update_time = now
            if status == "completed":
                row.memory_version += 1
                row.last_chat_time = now
                if already_answered is None:
                    row.message_count += 1
                if row.title == "新对话":
                    row.title = prepared.user_message.content[:40]
            return row.memory_version

    async def turns(self, prepared: PreparedGeneration, pairs: int = 11) -> list[dict]:
        async with self.sessions() as db:
            latest = (
                select(func.max(Message.id))
                .where(
                    Message.session_id == prepared.conversation.session_id,
                    Message.role == "assistant",
                    Message.status == "completed",
                )
                .group_by(Message.reply_to_id)
                .order_by(Message.reply_to_id.desc())
                .limit(pairs)
            )
            ids = list(await db.scalars(latest))
            if not ids:
                return []
            assistants = list(
                await db.scalars(
                    select(Message)
                    .options(*([undefer(Message.recommendations)] if self.game_tools else []))
                    .where(Message.id.in_(ids))
                    .order_by(Message.reply_to_id)
                )
            )
            users = {
                row.id: row
                for row in await db.scalars(
                    select(Message).where(
                        Message.id.in_([row.reply_to_id for row in assistants]),
                    )
                )
            }
            turns = []
            for assistant in assistants:
                user = users[assistant.reply_to_id]
                content = history_content(assistant)
                turns.append({"id": user.id, "user": user.content, "assistant": content})
            return turns
