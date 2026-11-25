import base64
import os
from unittest.mock import AsyncMock

import httpx
import pytest
from pydantic import SecretStr
from sqlalchemy import select
from test_persistence_service import Runtime, post
from test_persistence_store import chat
from test_persistence_store import store as store

from app.bots.contracts import (
    BotCreate,
    BotDelete,
    BotQuery,
    BotUpdate,
    CredentialCreate,
    CredentialRevoke,
    CredentialRotate,
    PlatformBotRequest,
    PromptQuery,
)
from app.bots.crypto import CredentialCipher
from app.bots.models import Bot, Credential, PromptVersion
from app.bots.runtime import RuntimeProvider
from app.bots.store import BotStore
from app.main import create_app
from app.persistence.context import DurableContext
from app.persistence.contracts import MessageQuery, PersistenceError, SessionCreate, SessionQuery
from app.persistence.service import PersistentChat


@pytest.fixture
def bot_settings(settings):
    return settings.model_copy(
        update={
            "personalization_enabled": True,
            "persistence_enabled": True,
            "redis_url": None,
            "credential_encryption_keys": {
                "v1": SecretStr(base64.b64encode(os.urandom(32)).decode())
            },
            "asset_hosts": ["assets.example.com"],
        }
    )


@pytest.fixture
def bots(store, bot_settings):
    store.bots = BotStore(store.sessions, bot_settings)
    return store.bots


async def credential(bots, user=7, key="qa-user-secret-1234"):
    return await bots.create_credential(
        CredentialCreate(
            user_id=user, provider_code="dashscope", label="QA", api_key=SecretStr(key)
        )
    )


async def custom(bots, user=7, key="qa-user-secret-1234"):
    saved = await credential(bots, user, key)
    return await bots.create_bot(
        BotCreate(
            user_id=user,
            girl_code="emily",
            name="My Bot",
            persona="friendly",
            credential_id=int(saved["id"]),
            model="qwen-plus",
        )
    )


@pytest.mark.asyncio
async def test_key_is_encrypted_bound_to_owner_and_never_returned(bots):
    saved = await credential(bots, key=" qa-user-secret-1234 ")
    assert "qa-user-secret" not in str(saved) and saved["maskedKey"] == "****1234"
    async with bots.sessions() as db:
        row = await db.get(Credential, int(saved["id"]))
        assert b"qa-user-secret" not in row.ciphertext
        assert bots.cipher.decrypt(row) == "qa-user-secret-1234"
        row.user_id = 8
        with pytest.raises(PersistenceError, match="CREDENTIAL_UNAVAILABLE"):
            bots.cipher.decrypt(row)


@pytest.mark.asyncio
async def test_key_rotation_revision_and_revoke_erase_ciphertext(bots):
    saved = await credential(bots)
    request = CredentialRotate(
        user_id=7,
        credential_id=int(saved["id"]),
        revision=1,
        api_key=SecretStr("new-user-key-5678"),
    )
    updated = await bots.rotate_credential(request)
    assert updated["revision"] == "2"
    with pytest.raises(PersistenceError, match="REVISION_CONFLICT"):
        await bots.rotate_credential(request)
    await bots.revoke_credential(
        CredentialRevoke(user_id=7, credential_id=int(saved["id"]), revision=2)
    )
    async with bots.sessions() as db:
        row = await db.get(Credential, int(saved["id"]))
        assert row.status == 0 and row.ciphertext is row.nonce is row.encryption_key_id is None
    with pytest.raises(PersistenceError, match="CREDENTIAL_UNAVAILABLE"):
        await bots.rotate_credential(request.model_copy(update={"revision": 3}))


@pytest.mark.asyncio
async def test_cipher_supports_old_key_ids_after_master_key_rotation(bots, bot_settings):
    saved = await credential(bots)
    rotated = bot_settings.model_copy(
        update={
            "credential_active_key_id": "v2",
            "credential_encryption_keys": {
                **bot_settings.credential_encryption_keys,
                "v2": SecretStr(base64.b64encode(os.urandom(32)).decode()),
            },
        }
    )
    cipher = CredentialCipher(rotated)
    async with bots.sessions() as db:
        row = await db.get(Credential, int(saved["id"]))
        assert cipher.decrypt(row) == "qa-user-secret-1234"
        row.revision += 1
        cipher.encrypt(row, "another-secret-9876")
        assert row.encryption_key_id == "v2" and cipher.decrypt(row) == "another-secret-9876"


@pytest.mark.asyncio
async def test_templates_create_private_platform_instances_and_custom_bots(bots):
    first = await bots.platform(PlatformBotRequest(user_id=7, girl_code="emily"))
    same = await bots.platform(PlatformBotRequest(user_id=7, girl_code="emily"))
    other = await bots.platform(PlatformBotRequest(user_id=8, girl_code="emily"))
    assert first["id"] == same["id"] != other["id"]
    personal = await custom(bots)
    assert personal["id"] != first["id"] and personal["kind"] == "custom"
    rows = await bots.list_bots(BotQuery(user_id=7, limit=1))
    assert rows["hasMore"] and len(rows["items"]) == 1
    older = await bots.list_bots(BotQuery(user_id=7, before_id=int(rows["nextCursor"])))
    assert len(older["items"]) == 1


@pytest.mark.asyncio
async def test_bot_and_credential_operations_enforce_ownership(bots):
    bot = await custom(bots)
    for operation in (bots.get_bot, bots.delete_bot):
        request = BotDelete(user_id=8, bot_id=int(bot["id"]), revision=1)
        with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
            await operation(request)
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await bots.prompt_versions(PromptQuery(user_id=8, bot_id=int(bot["id"])))
    with pytest.raises(PersistenceError, match="CREDENTIAL_NOT_FOUND"):
        await bots.create_bot(
            BotCreate(
                user_id=8,
                girl_code="emily",
                name="x",
                credential_id=int(bot["credentialId"]),
                model="qwen-plus",
            )
        )
    with pytest.raises(PersistenceError, match="CREDENTIAL_NOT_FOUND"):
        await bots.revoke_credential(
            CredentialRevoke(user_id=8, credential_id=int(bot["credentialId"]), revision=1)
        )


@pytest.mark.asyncio
async def test_prompt_versions_are_immutable_and_stale_edits_conflict(bots):
    bot = await custom(bots)
    request = BotUpdate(
        user_id=7,
        bot_id=int(bot["id"]),
        revision=1,
        name="Updated",
        persona="new persona",
        credential_id=int(bot["credentialId"]),
        model="qwen-plus",
    )
    saved = await bots.update_bot(request)
    assert saved["promptVersion"] == 2 and saved["revision"] == "2"
    with pytest.raises(PersistenceError, match="REVISION_CONFLICT"):
        await bots.update_bot(request)
    versions = await bots.prompt_versions(PromptQuery(user_id=7, bot_id=int(bot["id"])))
    assert [row["persona"] for row in versions["items"]] == ["new persona", "friendly"]
    async with bots.sessions() as db:
        old = await db.scalar(
            select(PromptVersion).where(
                PromptVersion.bot_id == int(bot["id"]), PromptVersion.version == 1
            )
        )
        assert old.system_prompt.endswith("friendly")


@pytest.mark.asyncio
async def test_personal_bot_requires_own_credential_and_platform_protects_persona(bots):
    bot = await custom(bots)
    request = BotUpdate(user_id=7, bot_id=int(bot["id"]), revision=1, name="x", model="qwen-plus")
    with pytest.raises(PersistenceError, match="CREDENTIAL_REQUIRED"):
        await bots.update_bot(request)
    platform = await bots.platform(PlatformBotRequest(user_id=7, girl_code="emily"))
    with pytest.raises(PersistenceError, match="PLATFORM_BOT_PROTECTED"):
        await bots.update_bot(
            request.model_copy(update={"bot_id": int(platform["id"]), "persona": "override"})
        )


@pytest.mark.parametrize(
    "url",
    [
        "javascript:alert(1)",
        "http://assets.example.com/a.png",
        "https://evil.example/a.png",
        "/assets/ai/../../a.png",
        "https://assets.example.com:bad/a.png",
    ],
)
def test_asset_url_validation_rejects_untrusted_sources(bots, url):
    with pytest.raises(PersistenceError, match="INVALID_BOT_ASSET"):
        bots.asset(url)


@pytest.mark.parametrize("kind", ["avatars", "portraits"])
def test_managed_assets_require_the_existing_same_field_binding(bots, kind):
    url = f"/api/files/ai-bot-{kind}/{'a' * 32}"
    bots.asset(url, current=url, kind=kind)
    for replacement in ("", "/assets/ai/avatar.png", url.replace("a" * 32, "b" * 32)):
        with pytest.raises(PersistenceError, match="MANAGED_BOT_ASSET_REQUIRED"):
            bots.asset(replacement, current=url, kind=kind)
    with pytest.raises(PersistenceError, match="MANAGED_BOT_ASSET_REQUIRED"):
        bots.asset(url)
    with pytest.raises(PersistenceError, match="MANAGED_BOT_ASSET_REQUIRED"):
        bots.asset(url, current=url, kind="portraits" if kind == "avatars" else "avatars")


@pytest.mark.asyncio
async def test_updating_configuration_preserves_managed_images_and_rejects_rebinding(bots):
    saved = await custom(bots)
    url = f"/api/files/ai-bot-avatars/{'c' * 32}"
    async with bots.sessions.begin() as db:
        bot = await db.get(Bot, int(saved["id"]))
        bot.avatar_url = url
    request = BotUpdate(
        user_id=7, bot_id=int(saved["id"]), revision=1, name="Updated",
        persona="friendly", avatar_url=url, credential_id=int(saved["credentialId"]),
        model="qwen-plus",
    )
    updated = await bots.update_bot(request)
    assert updated["avatarUrl"] == url and updated["revision"] == "2"
    with pytest.raises(PersistenceError, match="MANAGED_BOT_ASSET_REQUIRED"):
        await bots.update_bot(request.model_copy(update={"revision": 2, "avatar_url": ""}))


@pytest.mark.asyncio
async def test_chat_resolves_private_key_and_prompt_snapshot_without_cached_platform_key(
    store, bots, bot_settings
):
    assert bot_settings.chat_api_key_value == "test-provider-key"
    bot = await custom(bots)
    created = await store.create_session(SessionCreate(user_id=7, bot_id=int(bot["id"])))
    assert created["botId"] == bot["id"]
    request = chat(created["sessionId"])
    prepared = await store.begin(request)
    profile = prepared.profile
    assert profile.settings.chat_api_key_value == "qa-user-secret-1234"
    assert profile.system_prompt.endswith("friendly")
    assert prepared.assistant.prompt_version_id == profile.prompt_version_id
    assert prepared.assistant.credential_revision == 1
    await store.finish(prepared, "answer")
    await bots.revoke_credential(
        CredentialRevoke(user_id=7, credential_id=int(bot["credentialId"]), revision=1)
    )
    assert (await store.begin(request)).replay
    with pytest.raises(PersistenceError, match="CREDENTIAL_UNAVAILABLE"):
        await store.begin(chat(created["sessionId"], "new-message"))
    rows = await store.list_sessions(SessionQuery(user_id=7, bot_id=int(bot["id"])))
    assert len(rows["items"]) == 1


@pytest.mark.asyncio
async def test_runtime_clients_are_isolated_and_closed_even_after_cancellation(
    store, bots, bot_settings
):
    bot = await custom(bots)
    created = await store.create_session(SessionCreate(user_id=7, bot_id=int(bot["id"])))
    clients = []

    def factory(config, _memory):
        runtime = Runtime()
        runtime.aclose = AsyncMock()
        clients.append((config.chat_api_key_value, runtime))
        return runtime

    provider = RuntimeProvider(bot_settings, factory)
    service = PersistentChat(store, DurableContext(store), provider, 5)
    stream = service.events(chat(created["sessionId"]))
    await anext(stream)
    await anext(stream)
    await stream.aclose()
    assert clients[0][0] == "qa-user-secret-1234"
    clients[0][1].aclose.assert_awaited_once()
    assert clients[0][1].closed


@pytest.mark.asyncio
async def test_archiving_bot_requires_no_active_generation_and_history_is_retained(store, bots):
    bot = await custom(bots)
    session = await store.create_session(SessionCreate(user_id=7, bot_id=int(bot["id"])))
    prepared = await store.begin(chat(session["sessionId"]))
    request = BotDelete(user_id=7, bot_id=int(bot["id"]), revision=1)
    with pytest.raises(PersistenceError, match="CONVERSATION_BUSY"):
        await bots.delete_bot(request)
    await store.finish(prepared, "saved")
    await bots.delete_bot(request)
    history = await store.list_messages(MessageQuery(user_id=7, session_id=session["sessionId"]))
    assert history["items"][-1]["content"] == "saved"
    with pytest.raises(PersistenceError, match="BOT_NOT_FOUND"):
        await store.begin(chat(session["sessionId"], "new-key"))


@pytest.mark.asyncio
async def test_invalid_secret_validation_never_echoes_input(store, bot_settings):
    app = create_app(bot_settings, runtime=Runtime(), store=store)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await post(
                client,
                "/internal/v1/credentials/create",
                {
                    "userId": 7,
                    "providerCode": "dashscope",
                    "label": "QA",
                    "apiKey": "sensitive key with spaces",
                },
            )
            assert response.status_code == 422
            assert "sensitive key" not in response.text and "input" not in response.text
