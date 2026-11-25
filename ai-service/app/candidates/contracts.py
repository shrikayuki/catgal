# 阅读说明
# 候选提取结果及确认、拒绝、设置请求的数据校验。

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.bots.contracts import BotRequest
from app.models import to_camel


class CandidateSettingsRequest(BotRequest):
    model_config = ConfigDict(extra="forbid")


class CandidateSettingsUpdate(CandidateSettingsRequest):
    revision: int = Field(ge=0)
    enabled: bool


class CandidateQuery(CandidateSettingsRequest):
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)


class CandidateRequest(CandidateSettingsRequest):
    candidate_id: int = Field(gt=0)
    revision: int = Field(gt=0)


class CandidateConfirm(CandidateRequest):
    content: str = Field(min_length=1, max_length=1000)
    memory_type: Literal["preference", "fact"]


class ExtractedItem(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, extra="forbid", str_strip_whitespace=True
    )
    content: str = Field(min_length=1, max_length=1000)
    memory_type: Literal["preference", "fact"]


class ExtractedItems(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[ExtractedItem] = Field(max_length=3)
