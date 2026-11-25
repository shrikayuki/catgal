# 阅读说明
# MySQL 私有长期记忆映射，以用户和机器人共同隔离。
# 映射表包括 ai_memory。

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, ForeignKeyConstraint, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.persistence.models import IDENTITY, Base


class LongTermMemory(Base):
    __tablename__ = "ai_memory"
    __table_args__ = (ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),)
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    bot_id: Mapped[int] = mapped_column(IDENTITY)
    memory_type: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    source_message_id: Mapped[int | None] = mapped_column(IDENTITY, ForeignKey("ai_message.id"))
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    status: Mapped[int] = mapped_column(Integer, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
