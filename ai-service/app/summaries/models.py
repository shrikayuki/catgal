# 阅读说明
# 滚动摘要与来源版本、覆盖范围和生成状态的数据库映射。
# 映射表包括 ai_session_summary。

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    String,
    Text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.persistence.models import IDENTITY, Base


class SessionSummary(Base):
    __tablename__ = "ai_session_summary"
    __table_args__ = (ForeignKeyConstraint(["bot_id", "user_id"], ["ai_bot.id", "ai_bot.user_id"]),)

    session_id: Mapped[str] = mapped_column(
        String(256), ForeignKey("user_session.session_id"), primary_key=True
    )
    user_id: Mapped[int] = mapped_column(BigInteger)
    bot_id: Mapped[int] = mapped_column(IDENTITY)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    summary: Mapped[str] = mapped_column(Text, default="")
    covered_user_message_id: Mapped[int | None] = mapped_column(
        IDENTITY, ForeignKey("ai_message.id")
    )
    covered_assistant_message_id: Mapped[int | None] = mapped_column(
        IDENTITY, ForeignKey("ai_message.id")
    )
    covered_turns: Mapped[int] = mapped_column(Integer, default=0)
    source_memory_version: Mapped[int | None] = mapped_column(BigInteger)
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    status: Mapped[str] = mapped_column(String(16), default="empty")
    last_error_code: Mapped[str | None] = mapped_column(String(64))
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
