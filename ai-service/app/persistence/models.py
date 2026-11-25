# 阅读说明
# 角色、会话与消息数据库映射，保存请求幂等、生成状态、租约和模型快照。
# 映射表包括 ai_girl、user_session、ai_message。

from datetime import datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.mysql import MEDIUMTEXT
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

IDENTITY = BigInteger().with_variant(Integer, "sqlite")


class Base(DeclarativeBase):
    pass


class Character(Base):
    __tablename__ = "ai_girl"
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    girl_code: Mapped[str] = mapped_column(String(32), unique=True)
    name: Mapped[str] = mapped_column(String(32))
    avatar_url: Mapped[str | None] = mapped_column(String(255))
    system_prompt: Mapped[str] = mapped_column(Text)
    status: Mapped[int] = mapped_column(Integer, default=1)


class Conversation(Base):
    __tablename__ = "user_session"
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    girl_code: Mapped[str] = mapped_column(String(32))
    girl_id: Mapped[int] = mapped_column(BigInteger)
    session_id: Mapped[str] = mapped_column(String(256), unique=True)
    title: Mapped[str] = mapped_column(String(100), default="新对话")
    message_count: Mapped[int] = mapped_column(Integer, default=0)
    memory_version: Mapped[int] = mapped_column(BigInteger, default=0)
    active_message_id: Mapped[int | None] = mapped_column(BigInteger)
    active_lease_until: Mapped[datetime | None] = mapped_column(DateTime)
    status: Mapped[int] = mapped_column(Integer, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
    last_chat_time: Mapped[datetime | None] = mapped_column(DateTime)
    # Deferred columns preserve V9 operation until both services enable personalization.
    bot_id: Mapped[int | None] = mapped_column(
        BigInteger, deferred=True, server_default=text("NULL")
    )


class Message(Base):
    __tablename__ = "ai_message"
    __table_args__ = (
        UniqueConstraint("user_id", "client_message_id", name="uk_ai_message_client"),
    )
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(
        String(256), ForeignKey("user_session.session_id"), index=True
    )
    user_id: Mapped[int] = mapped_column(BigInteger)
    role: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text().with_variant(MEDIUMTEXT, "mysql"))
    status: Mapped[str] = mapped_column(String(16))
    reply_to_id: Mapped[int | None] = mapped_column(IDENTITY, ForeignKey("ai_message.id"))
    regenerated_from_id: Mapped[int | None] = mapped_column(IDENTITY, ForeignKey("ai_message.id"))
    client_message_id: Mapped[str | None] = mapped_column(String(64))
    request_id: Mapped[str | None] = mapped_column(String(36), unique=True)
    request_hash: Mapped[str | None] = mapped_column(String(64))
    generation_token: Mapped[str | None] = mapped_column(String(32))
    model: Mapped[str | None] = mapped_column(String(100))
    prompt_hash: Mapped[str | None] = mapped_column(String(64))
    error_code: Mapped[str | None] = mapped_column(String(64))
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
    finished_time: Mapped[datetime | None] = mapped_column(DateTime)
    bot_id: Mapped[int | None] = mapped_column(
        BigInteger, deferred=True, server_default=text("NULL")
    )
    prompt_version_id: Mapped[int | None] = mapped_column(
        BigInteger, deferred=True, server_default=text("NULL")
    )
    credential_id: Mapped[int | None] = mapped_column(
        BigInteger, deferred=True, server_default=text("NULL")
    )
    credential_revision: Mapped[int | None] = mapped_column(
        BigInteger, deferred=True, server_default=text("NULL")
    )
    provider_code: Mapped[str | None] = mapped_column(
        String(32), deferred=True, server_default=text("NULL")
    )
    recommendations: Mapped[list | None] = mapped_column(
        JSON, deferred=True, server_default=text("NULL")
    )
