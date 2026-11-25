# 阅读说明
# 摘要设置与并发版本请求协议。

from pydantic import ConfigDict, Field

from app.persistence.contracts import SessionRequest


class SummaryRequest(SessionRequest):
    model_config = ConfigDict(extra="forbid")


class SummarySettings(SummaryRequest):
    revision: int = Field(ge=0)
    enabled: bool


class SummaryClear(SummaryRequest):
    revision: int = Field(ge=0)
