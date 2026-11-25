# 阅读说明
# 旧聊天入口的请求模型与字段命名约定，Pydantic 在路由入口执行边界验证。

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ChatStreamRequest(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        str_strip_whitespace=True,
    )

    request_id: str = Field(min_length=8, max_length=128)
    user_id: int = Field(gt=0)
    session_id: str = Field(min_length=1, max_length=128)
    girl_code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    message: str = Field(min_length=1)
    system_prompt: str = Field(min_length=1, max_length=8000)
