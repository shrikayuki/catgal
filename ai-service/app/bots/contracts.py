# 阅读说明
# 个人机器人、模型凭据和提示词版本的 Pydantic 请求契约。
# 字符范围、长度和 revision 属于输入边界，所有者规则在仓储执行。

from pydantic import Field, SecretStr, field_validator

from app.persistence.contracts import InternalRequest


class CredentialCreate(InternalRequest):
    provider_code: str = Field(pattern=r"^[a-z][a-z0-9_-]{0,31}$")
    label: str = Field(min_length=1, max_length=64)
    api_key: SecretStr

    @field_validator("api_key")
    @classmethod
    def validate_key(cls, value):
        secret = value.get_secret_value().strip()
        if (
            not 8 <= len(secret) <= 1024
            or not secret.isascii()
            or any(char.isspace() for char in secret)
        ):
            raise ValueError("Invalid API key format")
        return SecretStr(secret)


class CredentialRequest(InternalRequest):
    credential_id: int = Field(gt=0)


class CredentialRotate(CredentialRequest):
    revision: int = Field(gt=0)
    api_key: SecretStr
    validate_key = field_validator("api_key")(CredentialCreate.validate_key.__func__)


class CredentialRevoke(CredentialRequest):
    revision: int = Field(gt=0)


class BotRequest(InternalRequest):
    bot_id: int = Field(gt=0)


class BotCreate(InternalRequest):
    girl_code: str = Field(default="catgal-assistant", pattern=r"^[A-Za-z0-9_-]{1,32}$")
    name: str = Field(min_length=1, max_length=64)
    persona: str = Field(default="", max_length=4000)
    avatar_url: str = Field(default="", max_length=1024)
    portrait_url: str = Field(default="", max_length=1024)
    credential_id: int = Field(gt=0)
    model: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$")


class BotUpdate(BotRequest):
    revision: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=64)
    persona: str = Field(default="", max_length=4000)
    avatar_url: str = Field(default="", max_length=1024)
    portrait_url: str = Field(default="", max_length=1024)
    credential_id: int | None = Field(default=None, gt=0)
    model: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$")


class BotDelete(BotRequest):
    revision: int = Field(gt=0)


class PlatformBotRequest(InternalRequest):
    girl_code: str = Field(pattern=r"^[A-Za-z0-9_-]{1,32}$")


class BotQuery(InternalRequest):
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)


class PromptQuery(BotRequest):
    before_id: int | None = Field(default=None, gt=0)
    limit: int = Field(default=20, ge=1, le=100)
