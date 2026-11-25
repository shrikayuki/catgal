# 阅读说明
# 候选提取设置、执行任务与候选记忆的持久化映射。
# 映射表包括 ai_memory_suggestion_settings、ai_memory_extraction、ai_memory_candidate。

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.persistence.models import IDENTITY, Base


class SuggestionSettings(Base):
    __tablename__ = "ai_memory_suggestion_settings"
    __table_args__ = (ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),)
    bot_id: Mapped[int] = mapped_column(IDENTITY, primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    enabled_after_message_id: Mapped[int] = mapped_column(BigInteger, default=0)
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)


class Extraction(Base):
    __tablename__ = "ai_memory_extraction"
    __table_args__ = (ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),)
    source_message_id: Mapped[int] = mapped_column(
        IDENTITY, ForeignKey("ai_message.id"), primary_key=True
    )
    user_id: Mapped[int] = mapped_column(BigInteger)
    bot_id: Mapped[int] = mapped_column(IDENTITY)
    session_id: Mapped[str] = mapped_column(String(256), ForeignKey("user_session.session_id"))
    source_hash: Mapped[str] = mapped_column(String(64))
    settings_revision: Mapped[int] = mapped_column(BigInteger)
    generation_message_id: Mapped[int] = mapped_column(IDENTITY, ForeignKey("ai_message.id"))
    generation_token: Mapped[str] = mapped_column(String(32))
    lease_until: Mapped[datetime] = mapped_column(DateTime)
    state: Mapped[str] = mapped_column(String(16), default="running")
    error_code: Mapped[str | None] = mapped_column(String(64))
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)


class Candidate(Base):
    __tablename__ = "ai_memory_candidate"
    __table_args__ = (
        ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),
        UniqueConstraint("user_id", "bot_id", "source_message_id", "content_hash"),
    )
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    bot_id: Mapped[int] = mapped_column(IDENTITY)
    source_message_id: Mapped[int] = mapped_column(
        IDENTITY, ForeignKey("ai_memory_extraction.source_message_id")
    )
    source_session_id: Mapped[str] = mapped_column(
        String(256), ForeignKey("user_session.session_id")
    )
    source_hash: Mapped[str] = mapped_column(String(64))
    content_hash: Mapped[str] = mapped_column(String(64))
    content: Mapped[str] = mapped_column(Text)
    memory_type: Mapped[str] = mapped_column(String(16))
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    state: Mapped[str] = mapped_column(String(16), default="pending")
    confirmed_memory_id: Mapped[int | None] = mapped_column(IDENTITY, ForeignKey("ai_memory.id"))
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
