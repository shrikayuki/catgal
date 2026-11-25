# 阅读说明
# 私有向量授权设置与同步队列的数据库映射。
# 映射表包括 ai_memory_vector_settings、ai_memory_vector_outbox。

from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKeyConstraint, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.persistence.models import IDENTITY, Base


class MemoryVectorSettings(Base):
    __tablename__ = "ai_memory_vector_settings"
    __table_args__ = (ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),)
    bot_id: Mapped[int] = mapped_column(IDENTITY, primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)


class MemoryVectorOutbox(Base):
    __tablename__ = "ai_memory_vector_outbox"
    memory_id: Mapped[int] = mapped_column(IDENTITY, primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    bot_id: Mapped[int] = mapped_column(BigInteger)
    version: Mapped[int] = mapped_column(BigInteger, default=1)
    pending: Mapped[bool] = mapped_column(Boolean, default=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    next_attempt_at: Mapped[datetime] = mapped_column(DateTime)
    last_error: Mapped[str | None] = mapped_column(String(64))
    dirty_since: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
