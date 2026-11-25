# 阅读说明
# 会话、消息、重新生成和幂等错误的协议类型。

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.models import to_camel


class InternalRequest(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, str_strip_whitespace=True
    )
    user_id: int = Field(gt=0)


class SessionCreate(InternalRequest):
    girl_code: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,32}$")
    bot_id: int | None = Field(default=None, gt=0)
    title: str = Field(default="新对话", min_length=1, max_length=100)

    @model_validator(mode="after")
    def require_bot_or_template(self):
        if (self.girl_code is None) == (self.bot_id is None):
            raise ValueError("Provide girlCode or botId")
        return self


class SessionQuery(InternalRequest):
    girl_code: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,32}$")
    bot_id: int | None = Field(default=None, gt=0)
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)


class SessionRequest(InternalRequest):
    session_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,256}$")
    girl_code: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,32}$")


class MessageQuery(SessionRequest):
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)


class PersistentChatRequest(SessionRequest):
    request_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    client_message_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    content: str | None = Field(default=None, min_length=1, max_length=4000)
    use_interests: bool = False
    use_game_tools: bool = False
    use_semantic_search: bool = False
    regenerate_message_id: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def require_one_action(self):
        if self.use_semantic_search and not self.use_game_tools:
            raise ValueError("Semantic search requires game tools")
        if (self.content is None) == (self.regenerate_message_id is None):
            raise ValueError("Provide content or regenerateMessageId")
        return self


class PersistenceError(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(code)
        self.code = code
        self.message = message
        self.status = status
