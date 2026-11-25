# 阅读说明
# 个人机器人、提示词版本与加密凭据的 SQLAlchemy 数据映射。
# 映射表包括 ai_user_credential、ai_bot、ai_bot_prompt_version。

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    LargeBinary,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.mysql import MEDIUMTEXT
from sqlalchemy.orm import Mapped, mapped_column

from app.persistence.models import IDENTITY, Base


class Credential(Base):
    __tablename__ = "ai_user_credential"
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, index=True)
    provider_code: Mapped[str] = mapped_column(String(32))
    label: Mapped[str] = mapped_column(String(64))
    ciphertext: Mapped[bytes | None] = mapped_column(LargeBinary)
    nonce: Mapped[bytes | None] = mapped_column(LargeBinary)
    encryption_key_id: Mapped[str | None] = mapped_column(String(32))
    masked_suffix: Mapped[str] = mapped_column(String(4))
    status: Mapped[int] = mapped_column(Integer, default=0)
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)


class Bot(Base):
    __tablename__ = "ai_bot"
    __table_args__ = (
        UniqueConstraint("user_id", "platform_template_id"),
        UniqueConstraint("id", "user_id"),
    )
    # V11 databases have no memory_enabled column. Do not eagerly fetch its default on INSERT.
    __mapper_args__ = {"eager_defaults": False}
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    template_id: Mapped[int] = mapped_column(IDENTITY, ForeignKey("ai_girl.id"))
    kind: Mapped[str] = mapped_column(String(16))
    platform_template_id: Mapped[int | None] = mapped_column(BigInteger)
    name: Mapped[str] = mapped_column(String(64))
    avatar_url: Mapped[str] = mapped_column(String(1024), default="")
    portrait_url: Mapped[str] = mapped_column(String(1024), default="")
    credential_id: Mapped[int | None] = mapped_column(IDENTITY, ForeignKey("ai_user_credential.id"))
    model: Mapped[str] = mapped_column(String(100))
    prompt_version: Mapped[int] = mapped_column(Integer, default=1)
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    status: Mapped[int] = mapped_column(Integer, default=1)
    create_time: Mapped[datetime] = mapped_column(DateTime)
    update_time: Mapped[datetime] = mapped_column(DateTime)
    memory_enabled: Mapped[bool] = mapped_column(Boolean, deferred=True, server_default=text("0"))


class PromptVersion(Base):
    __tablename__ = "ai_bot_prompt_version"
    __table_args__ = (UniqueConstraint("bot_id", "version"),)
    id: Mapped[int] = mapped_column(IDENTITY, primary_key=True, autoincrement=True)
    bot_id: Mapped[int] = mapped_column(IDENTITY, ForeignKey("ai_bot.id"))
    version: Mapped[int] = mapped_column(Integer)
    persona: Mapped[str] = mapped_column(Text)
    system_prompt: Mapped[str] = mapped_column(Text().with_variant(MEDIUMTEXT, "mysql"))
    create_time: Mapped[datetime] = mapped_column(DateTime)
