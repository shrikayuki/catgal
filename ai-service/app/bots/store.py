# 阅读说明
# 个人机器人、凭据、提示词版本和生成配置的事务仓储。
# 每次操作核验 user_id 归属与 revision，平台角色和个人角色按凭据规则解析。

import re
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from pydantic import SecretStr
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.bots.crypto import CredentialCipher
from app.bots.models import Bot, Credential, PromptVersion
from app.config import Settings
from app.persistence.contracts import PersistenceError
from app.persistence.models import Character, Conversation, Message


def credential_view(row):
    return {
        "id": str(row.id),
        "providerCode": row.provider_code,
        "label": row.label,
        "maskedKey": "****" + row.masked_suffix,
        "status": row.status,
        "revision": str(row.revision),
        "updateTime": row.update_time.isoformat() + "Z",
    }


def bot_view(row, persona=None):
    result = {
        "id": str(row.id),
        "kind": row.kind,
        "name": row.name,
        "templateId": str(row.template_id),
        "avatarUrl": row.avatar_url,
        "portraitUrl": row.portrait_url,
        "credentialId": str(row.credential_id) if row.credential_id else None,
        "model": row.model,
        "promptVersion": row.prompt_version,
        "revision": str(row.revision),
        "createTime": row.create_time.isoformat() + "Z",
    }
    if persona is not None:
        result["persona"] = persona
    return result


@dataclass
class GenerationProfile:
    bot_id: int
    prompt_version_id: int
    system_prompt: str = field(repr=False)
    model: str
    provider_code: str
    credential_id: int | None = None
    credential_revision: int | None = None
    settings: Settings | None = field(default=None, repr=False)


class BotStore:
    def __init__(self, sessions, settings: Settings):
        self.sessions = sessions
        self.settings = settings
        self.cipher = CredentialCipher(settings)
        for endpoint in settings.provider_endpoints.values():
            url = urlsplit(endpoint)
            if (
                not url.hostname
                or url.username
                or url.password
                or url.query
                or url.fragment
                or (url.scheme != "https" and settings.app_env != "test")
            ):
                raise RuntimeError("Invalid model provider endpoint configuration")

    async def health(self):
        async with self.sessions() as db:
            await db.execute(select(Bot.id, Bot.prompt_version).limit(1))
            await db.execute(select(Credential.id, Credential.encryption_key_id).limit(1))
            await db.execute(select(PromptVersion.id).limit(1))
            await db.execute(select(Conversation.bot_id).limit(1))
            await db.execute(
                select(
                    Message.bot_id,
                    Message.prompt_version_id,
                    Message.credential_revision,
                    Message.provider_code,
                ).limit(1)
            )
        return True

    async def _now(self, db):
        return await db.scalar(select(func.current_timestamp()))

    def provider(self, code):
        endpoint = self.settings.provider_endpoints.get(code)
        if endpoint is None:
            raise PersistenceError("PROVIDER_UNAVAILABLE", "模型来源未启用", 400)
        return endpoint

    def asset(self, value, *, current=None, kind=None):
        managed_path = r"/api/files/ai-bot-(avatars|portraits)/[0-9a-f]{32}"
        if re.fullmatch(managed_path, value or "") or re.fullmatch(managed_path, current or ""):
            # 托管图片由 Java 在同一事务内更新引用及清理任务，配置保存仅接受当前绑定。
            if value != current or not value.startswith(f"/api/files/ai-bot-{kind}/"):
                raise PersistenceError(
                    "MANAGED_BOT_ASSET_REQUIRED", "请通过本地选图入口更新这张图片", 400
                )
            return
        if not value:
            return
        if re.fullmatch(r"/assets/ai/[A-Za-z0-9_/-]+\.(png|jpg|jpeg|webp)", value):
            return
        try:
            url = urlsplit(value)
            port = url.port
        except ValueError:
            raise PersistenceError("INVALID_BOT_ASSET", "形象地址格式不正确", 400) from None
        if (
            url.scheme == "https"
            and url.hostname in self.settings.asset_hosts
            and not url.username
            and not url.password
            and port in (None, 443)
            and not url.query
            and not url.fragment
            and re.search(r"\.(png|jpg|jpeg|webp)$", url.path, re.IGNORECASE)
        ):
            return
        raise PersistenceError("INVALID_BOT_ASSET", "形象地址须来自已配置的图片来源", 400)

    async def _credential(self, db, user_id, credential_id, active=True):
        row = await db.scalar(
            select(Credential)
            .where(Credential.id == credential_id, Credential.user_id == user_id)
            .with_for_update()
        )
        if row is None:
            raise PersistenceError("CREDENTIAL_NOT_FOUND", "模型凭据不存在", 404)
        if active and row.status != 1:
            raise PersistenceError("CREDENTIAL_UNAVAILABLE", "模型凭据已撤销", 409)
        return row

    async def create_credential(self, request):
        self.provider(request.provider_code)
        async with self.sessions.begin() as db:
            now = await self._now(db)
            row = Credential(
                user_id=request.user_id,
                provider_code=request.provider_code,
                label=request.label,
                masked_suffix="****",
                status=0,
                create_time=now,
                update_time=now,
            )
            db.add(row)
            await db.flush()
            self.cipher.encrypt(row, request.api_key.get_secret_value())
            return credential_view(row)

    async def list_credentials(self, request):
        async with self.sessions() as db:
            query = select(Credential).where(Credential.user_id == request.user_id)
            if request.before_id is not None:
                query = query.where(Credential.id < request.before_id)
            rows = list(
                await db.scalars(query.order_by(Credential.id.desc()).limit(request.limit + 1))
            )
            visible = rows[: request.limit]
            return {
                "items": [credential_view(row) for row in visible],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
            }

    # 更新凭据时推进修订号并重新加密，已读取旧版本的编辑请求会冲突。
    async def rotate_credential(self, request):
        async with self.sessions.begin() as db:
            row = await self._credential(db, request.user_id, request.credential_id)
            self.revision(row, request.revision)
            row.revision += 1
            self.cipher.encrypt(row, request.api_key.get_secret_value())
            row.update_time = await self._now(db)
            return credential_view(row)

    async def revoke_credential(self, request):
        async with self.sessions.begin() as db:
            row = await self._credential(db, request.user_id, request.credential_id, active=False)
            if row.status == 0:
                return credential_view(row)
            self.revision(row, request.revision)
            row.status = 0
            row.ciphertext = row.nonce = row.encryption_key_id = None
            row.revision += 1
            row.update_time = await self._now(db)
            return credential_view(row)

    def revision(self, row, expected):
        if row.revision != expected:
            raise PersistenceError("REVISION_CONFLICT", "配置已更新，请重新读取后提交")

    async def template(self, db, code=None, template_id=None):
        condition = Character.girl_code == code if code is not None else Character.id == template_id
        row = await db.scalar(select(Character).where(condition, Character.status == 1))
        if row is None:
            raise PersistenceError("CHARACTER_UNAVAILABLE", "平台模板不存在或未启用", 404)
        return row

    async def templates(self, _request):
        async with self.sessions() as db:
            rows = await db.scalars(
                select(Character).where(Character.status == 1).order_by(Character.id)
            )
            return {
                "items": [
                    {
                        "id": str(row.id),
                        "girlCode": row.girl_code,
                        "name": row.name,
                        "avatarUrl": row.avatar_url,
                    }
                    for row in rows
                ]
            }

    async def ensure_platform(self, db, user_id, template):
        query = select(Bot).where(Bot.user_id == user_id, Bot.platform_template_id == template.id)
        row = await db.scalar(query.with_for_update())
        if row is not None:
            return row
        now = await self._now(db)
        try:
            async with db.begin_nested():
                row = Bot(
                    user_id=user_id,
                    template_id=template.id,
                    platform_template_id=template.id,
                    kind="platform",
                    name=template.name,
                    avatar_url=template.avatar_url or "",
                    model=self.settings.chat_model,
                    create_time=now,
                    update_time=now,
                )
                db.add(row)
                await db.flush()
                db.add(
                    PromptVersion(
                        bot_id=row.id,
                        version=1,
                        persona="",
                        system_prompt=template.system_prompt,
                        create_time=now,
                    )
                )
                await db.flush()
        except IntegrityError:
            row = await db.scalar(query.with_for_update())
            if row is None:
                raise
        return row

    async def platform(self, request):
        async with self.sessions.begin() as db:
            template = await self.template(db, code=request.girl_code)
            row = await self.ensure_platform(db, request.user_id, template)
            return bot_view(row, "")

    async def owned(self, db, user_id, bot_id):
        row = await db.scalar(
            select(Bot).where(Bot.id == bot_id, Bot.user_id == user_id).with_for_update()
        )
        if row is None or row.status != 1:
            raise PersistenceError("BOT_NOT_FOUND", "机器人不存在", 404)
        return row

    async def _prompt(self, db, bot):
        row = await db.scalar(
            select(PromptVersion).where(
                PromptVersion.bot_id == bot.id, PromptVersion.version == bot.prompt_version
            )
        )
        if row is None:
            raise PersistenceError("BOT_CONFIGURATION_ERROR", "机器人提示词版本不可用", 503)
        return row

    async def create_bot(self, request):
        self.asset(request.avatar_url)
        self.asset(request.portrait_url)
        async with self.sessions.begin() as db:
            template = await self.template(db, code=request.girl_code)
            await self._credential(db, request.user_id, request.credential_id)
            now = await self._now(db)
            row = Bot(
                user_id=request.user_id,
                template_id=template.id,
                kind="custom",
                name=request.name,
                avatar_url=request.avatar_url,
                portrait_url=request.portrait_url,
                credential_id=request.credential_id,
                model=request.model,
                create_time=now,
                update_time=now,
            )
            db.add(row)
            await db.flush()
            db.add(
                PromptVersion(
                    bot_id=row.id,
                    version=1,
                    persona=request.persona,
                    system_prompt=self.prompt(template, request.persona),
                    create_time=now,
                )
            )
            return bot_view(row, request.persona)

    def prompt(self, template, persona):
        return template.system_prompt + ("\n\n用户自定义角色设定\n" + persona if persona else "")

    async def update_bot(self, request):
        async with self.sessions.begin() as db:
            bot = await self.owned(db, request.user_id, request.bot_id)
            self.revision(bot, request.revision)
            self.asset(request.avatar_url, current=bot.avatar_url, kind="avatars")
            self.asset(request.portrait_url, current=bot.portrait_url, kind="portraits")
            current = await self._prompt(db, bot)
            if bot.kind == "platform" and (
                request.name != bot.name
                or request.persona
                or request.avatar_url != bot.avatar_url
                or request.portrait_url != bot.portrait_url
            ):
                raise PersistenceError(
                    "PLATFORM_BOT_PROTECTED", "平台形象可通过新建个人机器人进行定制", 409
                )
            if request.credential_id is None:
                if bot.kind != "platform":
                    raise PersistenceError(
                        "CREDENTIAL_REQUIRED", "个人机器人需要绑定自己的模型凭据", 400
                    )
                model = self.settings.chat_model
            else:
                await self._credential(db, request.user_id, request.credential_id)
                model = request.model
            now = await self._now(db)
            if request.persona != current.persona:
                template = await self.template(db, template_id=bot.template_id)
                bot.prompt_version += 1
                db.add(
                    PromptVersion(
                        bot_id=bot.id,
                        version=bot.prompt_version,
                        persona=request.persona,
                        system_prompt=self.prompt(template, request.persona),
                        create_time=now,
                    )
                )
            bot.name, bot.avatar_url, bot.portrait_url = (
                request.name,
                request.avatar_url,
                request.portrait_url,
            )
            bot.credential_id, bot.model = request.credential_id, model
            bot.revision += 1
            bot.update_time = now
            return bot_view(bot, request.persona)

    async def get_bot(self, request):
        async with self.sessions.begin() as db:
            bot = await self.owned(db, request.user_id, request.bot_id)
            prompt = await self._prompt(db, bot)
            return bot_view(bot, prompt.persona)

    async def list_bots(self, request):
        async with self.sessions() as db:
            query = select(Bot).where(Bot.user_id == request.user_id, Bot.status == 1)
            if request.before_id is not None:
                query = query.where(Bot.id < request.before_id)
            rows = list(await db.scalars(query.order_by(Bot.id.desc()).limit(request.limit + 1)))
            visible = rows[: request.limit]
            return {
                "items": [bot_view(row) for row in visible],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
            }

    async def prompt_versions(self, request):
        async with self.sessions.begin() as db:
            await self.owned(db, request.user_id, request.bot_id)
            query = select(PromptVersion).where(PromptVersion.bot_id == request.bot_id)
            if request.before_id is not None:
                query = query.where(PromptVersion.id < request.before_id)
            rows = list(
                await db.scalars(query.order_by(PromptVersion.id.desc()).limit(request.limit + 1))
            )
            visible = rows[: request.limit]
            return {
                "items": [
                    {
                        "id": str(row.id),
                        "version": row.version,
                        "persona": row.persona,
                        "createTime": row.create_time.isoformat() + "Z",
                    }
                    for row in visible
                ],
                "hasMore": len(rows) > request.limit,
                "nextCursor": str(visible[-1].id) if len(rows) > request.limit else None,
            }

    # 检查机器人当前是否仍有有效生成租约，再软删除并推进 revision。
    async def delete_bot(self, request):
        async with self.sessions.begin() as db:
            bot = await self.owned(db, request.user_id, request.bot_id)
            self.revision(bot, request.revision)
            if bot.kind == "platform":
                raise PersistenceError("PLATFORM_BOT_PROTECTED", "平台机器人保留供会话使用", 409)
            active = await db.scalar(
                select(Conversation.id)
                .where(
                    Conversation.bot_id == bot.id,
                    Conversation.status == 1,
                    Conversation.active_message_id.is_not(None),
                    Conversation.active_lease_until > await self._now(db),
                )
                .limit(1)
            )
            if active is not None:
                raise PersistenceError("CONVERSATION_BUSY", "机器人仍有生成中的会话", 409)
            bot.status = 0
            bot.revision += 1
            bot.update_time = await self._now(db)
            return {"deleted": True}

    # 每次生成在事务内核验机器人、模板和提示词，平台角色使用平台配置，个人角色解析本人凭据。
    async def resolve(self, db, conversation):
        template = await self.template(db, template_id=conversation.girl_id)
        if conversation.bot_id is None:
            bot = await self.ensure_platform(db, conversation.user_id, template)
            conversation.bot_id = bot.id
        else:
            bot = await self.owned(db, conversation.user_id, conversation.bot_id)
        if bot.template_id != conversation.girl_id:
            raise PersistenceError("BOT_CONFIGURATION_ERROR", "机器人模板关联异常", 503)
        prompt = await self._prompt(db, bot)
        if not prompt.system_prompt or len(prompt.system_prompt) > 16000:
            raise PersistenceError("BOT_CONFIGURATION_ERROR", "机器人提示词配置异常", 503)
        profile = GenerationProfile(
            bot.id, prompt.id, prompt.system_prompt, self.settings.chat_model, "platform"
        )
        if bot.credential_id is None:
            if bot.kind != "platform":
                raise PersistenceError("CREDENTIAL_REQUIRED", "个人机器人需要模型凭据", 409)
            return profile
        credential = await self._credential(db, conversation.user_id, bot.credential_id)
        endpoint = self.provider(credential.provider_code)
        values = self.settings.model_dump()
        values.update(
            chat_api_key=SecretStr(self.cipher.decrypt(credential)),
            chat_base_url=endpoint,
            chat_model=bot.model,
            model_max_retries=0,
        )
        profile.settings = Settings(_env_file=None, **values)
        profile.model = bot.model
        profile.provider_code = credential.provider_code
        profile.credential_id = credential.id
        profile.credential_revision = credential.revision
        return profile
