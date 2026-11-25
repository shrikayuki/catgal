# 阅读说明
# 私有记忆向量授权开关和 revision 输入协议。

from pydantic import ConfigDict, Field

from app.bots.contracts import BotRequest


class MemoryVectorRequest(BotRequest):
    model_config = ConfigDict(extra="forbid")


class MemoryVectorUpdate(MemoryVectorRequest):
    revision: int = Field(ge=0)
    enabled: bool
