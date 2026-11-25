# 阅读说明
# 个人机器人及凭据内部路由，开关验证后委托 BotStore 完成归属和修订校验。

from fastapi import APIRouter, Depends, Request

from app.bots.contracts import (
    BotCreate,
    BotDelete,
    BotQuery,
    BotRequest,
    BotUpdate,
    CredentialCreate,
    CredentialRevoke,
    CredentialRotate,
    PlatformBotRequest,
    PromptQuery,
)
from app.persistence.contracts import InternalRequest, PersistenceError
from app.security import verify_internal_request

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(verify_internal_request)])


def require_bots(request):
    store = request.app.state.store
    if (
        not request.app.state.settings.personalization_enabled
        or store is None
        or store.bots is None
    ):
        raise PersistenceError("PERSONALIZATION_UNAVAILABLE", "个人机器人功能未启用", 503)
    return store.bots


@router.post("/templates/list")
async def templates(payload: InternalRequest, request: Request):
    return await require_bots(request).templates(payload)


@router.post("/providers/list")
async def providers(_payload: InternalRequest, request: Request):
    store = require_bots(request)
    return {"items": [{"code": code} for code in sorted(store.settings.provider_endpoints)]}


@router.post("/bots/platform")
async def platform(payload: PlatformBotRequest, request: Request):
    return await require_bots(request).platform(payload)


@router.post("/bots/create")
async def create(payload: BotCreate, request: Request):
    return await require_bots(request).create_bot(payload)


@router.post("/bots/list")
async def list_bots(payload: BotQuery, request: Request):
    return await require_bots(request).list_bots(payload)


@router.post("/bots/get")
async def get(payload: BotRequest, request: Request):
    return await require_bots(request).get_bot(payload)


@router.post("/bots/update")
async def update(payload: BotUpdate, request: Request):
    return await require_bots(request).update_bot(payload)


@router.post("/bots/delete")
async def delete(payload: BotDelete, request: Request):
    return await require_bots(request).delete_bot(payload)


@router.post("/bots/prompts")
async def prompts(payload: PromptQuery, request: Request):
    return await require_bots(request).prompt_versions(payload)


@router.post("/credentials/create")
async def create_credential(payload: CredentialCreate, request: Request):
    return await require_bots(request).create_credential(payload)


@router.post("/credentials/list")
async def credentials(payload: BotQuery, request: Request):
    return await require_bots(request).list_credentials(payload)


@router.post("/credentials/rotate")
async def rotate(payload: CredentialRotate, request: Request):
    return await require_bots(request).rotate_credential(payload)


@router.post("/credentials/revoke")
async def revoke(payload: CredentialRevoke, request: Request):
    return await require_bots(request).revoke_credential(payload)
