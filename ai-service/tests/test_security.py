import json
import time

import pytest
from conftest import TEST_SECRET, signed_request
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError

from app.config import Settings
from app.security import NonceReplayGuard, compute_signature


def test_future_dated_signature_cannot_replay_after_one_window(client, chat_payload, monkeypatch):
    clock = [int(time.time())]
    monkeypatch.setattr("app.security.time.time", lambda: clock[0])
    client.app.state.replay_guard = NonceReplayGuard(now=lambda: clock[0])
    window = client.app.state.settings.request_max_age_seconds
    timestamp, nonce = str(clock[0] + window), "future-nonce-123456"
    path = "/internal/v1/chat/stream"
    body = json.dumps(chat_payload, ensure_ascii=False).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Catgal-Timestamp": timestamp,
        "X-Catgal-Nonce": nonce,
        "X-Catgal-Signature": compute_signature(TEST_SECRET, timestamp, nonce, "POST", path, body),
    }
    assert client.post(path, content=body, headers=headers).status_code == 200
    clock[0] += window + 1
    assert client.post(path, content=body, headers=headers).status_code == 401


def test_rejects_unsigned_request(client: TestClient, chat_payload: dict[str, object]) -> None:
    response = client.post("/internal/v1/chat/stream", json=chat_payload)

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid signature"}


def test_rejects_tampered_body(client: TestClient, chat_payload: dict[str, object]) -> None:
    response = signed_request(client, {**chat_payload, "message": "篡改"}, secret="wrong-secret")

    assert response.status_code == 401


def test_rejects_replayed_nonce(client: TestClient, chat_payload: dict[str, object]) -> None:
    first = signed_request(client, chat_payload, nonce="same-nonce-123456")
    second = signed_request(client, chat_payload, nonce="same-nonce-123456")

    assert first.status_code == 200
    assert second.status_code == 401


def test_rejects_expired_timestamp(
    client: TestClient,
    chat_payload: dict[str, object],
) -> None:
    path = "/internal/v1/chat/stream"
    timestamp = str(int(time.time()) - 3600)
    response = client.post(
        path,
        json=chat_payload,
        headers={
            "X-Catgal-Timestamp": timestamp,
            "X-Catgal-Nonce": "old-nonce-123456",
            "X-Catgal-Signature": "invalid",
        },
    )

    assert response.status_code == 401


def test_rejects_weak_hmac_secret_configuration() -> None:
    with pytest.raises(ValidationError, match="at least 32 characters"):
        Settings(service_hmac_secret=SecretStr("too-short"), _env_file=None)


def test_signature_matches_java_contract_vector() -> None:
    signature = compute_signature(
        "test-secret-that-is-long-enough-for-hmac",
        "1794042000",
        "nonce-12345678",
        "POST",
        "/internal/v1/chat/stream",
        '{"message":"你好"}'.encode(),
    )

    assert signature == "4233f297cee84b7ed7eb4f8187f455fd81b067752d9b3b18bc931110f19b9c92"
