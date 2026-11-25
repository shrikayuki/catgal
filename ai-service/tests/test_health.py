from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings
from app.main import create_app


class HealthyMemory:
    async def load(self, user_id: int, girl_code: str, session_id: str) -> list[dict[str, str]]:
        return []

    async def save_exchange(
        self,
        user_id: int,
        girl_code: str,
        session_id: str,
        user_message: str,
        assistant_message: str,
    ) -> None:
        return None

    async def health(self) -> bool:
        return True

    async def close(self) -> None:
        return None


def test_liveness_does_not_require_provider_configuration() -> None:
    with TestClient(
        create_app(
            settings=Settings(
                service_hmac_secret=None, chat_api_key=None, redis_url=None, _env_file=None
            )
        )
    ) as client:
        response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "alive"}


def test_readiness_reports_missing_secrets() -> None:
    with TestClient(
        create_app(
            settings=Settings(
                service_hmac_secret=None, chat_api_key=None, redis_url=None, _env_file=None
            )
        )
    ) as client:
        response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {
        "status": "not_ready",
        "checks": {"serviceAuth": False, "modelProvider": False, "redisMemory": False},
    }


def test_readiness_requires_reachable_memory() -> None:
    settings = Settings(
        service_hmac_secret=SecretStr("test-secret-that-is-long-enough-for-hmac"),
        chat_api_key=SecretStr("test-provider-key"),
        redis_url=SecretStr("redis://unused"),
        _env_file=None,
    )
    with TestClient(create_app(settings=settings, memory=HealthyMemory())) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "checks": {"serviceAuth": True, "modelProvider": True, "redisMemory": True},
    }
