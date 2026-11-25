# 阅读说明
# 长期记忆类型、内容、来源消息和 revision 的请求约束。

from typing import Literal

from pydantic import ConfigDict, Field

from app.bots.contracts import BotRequest


class MemorySettingsRequest(BotRequest):
    model_config = ConfigDict(extra="forbid")


class MemorySettingsUpdate(MemorySettingsRequest):
    revision: int = Field(gt=0)
    memory_enabled: bool


class MemoryQuery(MemorySettingsRequest):
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)


class MemoryCreate(MemorySettingsRequest):
    content: str = Field(min_length=1, max_length=1000)
    memory_type: Literal["preference", "fact"]
    source_message_id: int | None = Field(default=None, gt=0)


class MemoryUpdate(MemorySettingsRequest):
    memory_id: int = Field(gt=0)
    revision: int = Field(gt=0)
    content: str = Field(min_length=1, max_length=1000)
    memory_type: Literal["preference", "fact"]


class MemoryDelete(MemorySettingsRequest):
    memory_id: int = Field(gt=0)
    revision: int = Field(gt=0)
