# 阅读说明
# 自动记忆候选的设置、列表、用户确认和拒绝路由。

from fastapi import APIRouter, Depends, Request

from app.candidates.contracts import (
    CandidateConfirm,
    CandidateQuery,
    CandidateRequest,
    CandidateSettingsRequest,
    CandidateSettingsUpdate,
)
from app.persistence.contracts import PersistenceError
from app.security import verify_internal_request

router = APIRouter(
    prefix="/internal/v1/memory-candidates", dependencies=[Depends(verify_internal_request)]
)


def require_candidates(request):
    store = request.app.state.store
    if (
        not request.app.state.settings.memory_candidates_enabled
        or store is None
        or store.candidates is None
    ):
        raise PersistenceError("MEMORY_CANDIDATES_UNAVAILABLE", "候选记忆功能未启用或暂不可用", 503)
    return store.candidates


@router.post("/settings/get")
async def settings(payload: CandidateSettingsRequest, request: Request):
    return await require_candidates(request).settings(payload)


@router.post("/settings/update")
async def update_settings(payload: CandidateSettingsUpdate, request: Request):
    return await require_candidates(request).update_settings(payload)


@router.post("/list")
async def list_candidates(payload: CandidateQuery, request: Request):
    return await require_candidates(request).list(payload)


@router.post("/confirm")
async def confirm(payload: CandidateConfirm, request: Request):
    return await require_candidates(request).confirm(payload)


@router.post("/reject")
async def reject(payload: CandidateRequest, request: Request):
    return await require_candidates(request).reject(payload)
