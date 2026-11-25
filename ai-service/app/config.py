# 阅读说明
# Python AI 服务配置，Pydantic Settings 从环境读取开关、限额与 SecretStr 凭据。
# is_ready 与配置校验用于判断当前实例是否具备处理请求的依赖。

from functools import cached_property
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="AI_",
        extra="ignore",
    )

    app_env: Literal["local", "test", "production"] = "local"
    service_hmac_secret: SecretStr | None = None
    chat_api_key: SecretStr | None = None
    redis_url: SecretStr | None = None
    persistence_enabled: bool = False
    personalization_enabled: bool = False
    long_term_memory_enabled: bool = False
    memory_vector_enabled: bool = False
    memory_vector_worker_enabled: bool = False
    memory_vector_index_name: str = Field(
        default="catgal_memory_vectors_v1", pattern=r"^[a-z0-9][a-z0-9_-]{0,80}$"
    )
    memory_vector_timeout_seconds: float = Field(default=2, gt=0, le=2)
    memory_candidates_enabled: bool = False
    candidate_extraction_timeout_seconds: float = Field(default=8, gt=0, le=8)
    session_summary_enabled: bool = False
    summary_timeout_seconds: float = Field(default=12, gt=0, le=30)
    summary_batch_turns: int = Field(default=10, ge=1, le=10)
    summary_source_chars: int = Field(default=24000, ge=4000, le=24000)
    game_tools_enabled: bool = False
    semantic_search_enabled: bool = False
    semantic_worker_enabled: bool = False
    semantic_index_name: str = Field(
        default="catgal_game_vectors_v1", pattern=r"^[a-z0-9][a-z0-9_-]{0,80}$"
    )
    semantic_es_url: str = "http://127.0.0.1:9200"
    semantic_es_username: str | None = None
    semantic_es_password: SecretStr | None = None
    embedding_api_key: SecretStr | None = None
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_model: str = "text-embedding-v4"
    embedding_dimensions: int = Field(default=1024, ge=64, le=2048)
    embedding_timeout_seconds: float = Field(default=15, gt=0, le=60)
    semantic_timeout_seconds: float = Field(default=3, gt=0, le=3)
    java_tools_base_url: str = "http://127.0.0.1:8080"
    tool_timeout_seconds: float = Field(default=5, gt=0, le=15)
    max_tool_calls: int = Field(default=4, ge=1, le=6)
    credential_encryption_keys: dict[str, SecretStr] = Field(default_factory=dict)
    credential_active_key_id: str = "v1"
    provider_endpoints: dict[str, str] = Field(
        default_factory=lambda: {
            "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        }
    )
    asset_hosts: list[str] = Field(default_factory=list)
    mysql_host: str | None = None
    mysql_port: int = Field(default=3306, ge=1, le=65535)
    mysql_database: str = "catgal"
    mysql_username: str | None = None
    mysql_password: SecretStr | None = None
    chat_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    chat_model: str = "qwen-plus"
    model_timeout_seconds: float = Field(default=60.0, gt=0, le=300)
    model_max_retries: int = Field(default=0, ge=0, le=10)
    model_temperature: float = Field(default=0.7, ge=0, le=2)
    model_max_completion_tokens: int = Field(default=2048, ge=1, le=32768)
    request_max_age_seconds: int = Field(default=60, ge=1, le=300)
    max_concurrent_requests: int = Field(default=20, ge=1, le=1000)
    max_input_chars: int = Field(default=4000, ge=1, le=50000)
    stream_timeout_seconds: float = Field(default=75.0, gt=0, le=300)
    model_failure_threshold: int = Field(default=3, ge=1, le=100)
    model_recovery_seconds: float = Field(default=30.0, gt=0, le=600)

    @field_validator("service_hmac_secret")
    @classmethod
    def validate_hmac_secret(cls, value: SecretStr | None) -> SecretStr | None:
        if value and len(value.get_secret_value()) < 32:
            raise ValueError("AI_SERVICE_HMAC_SECRET must contain at least 32 characters")
        return value

    @cached_property
    def hmac_secret_value(self) -> str | None:
        return self.service_hmac_secret.get_secret_value() if self.service_hmac_secret else None

    @cached_property
    def chat_api_key_value(self) -> str | None:
        return self.chat_api_key.get_secret_value() if self.chat_api_key else None

    @cached_property
    def redis_url_value(self) -> str | None:
        return self.redis_url.get_secret_value() if self.redis_url else None

    @property
    def is_ready(self) -> bool:
        return bool(self.hmac_secret_value and self.chat_api_key_value and self.redis_url_value)
