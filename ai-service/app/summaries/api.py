# 阅读说明
# 会话摘要读取、启停和清空路由，统一要求本人会话。

from fastapi import APIRouter, Depends, Request

from app.persistence.contracts import PersistenceError
from app.security import verify_internal_request
from app.summaries.contracts import SummaryClear, SummaryRequest, SummarySettings

router = APIRouter(prefix="/internal/v1/summary", dependencies=[Depends(verify_internal_request)])


def require_summary(request):
    store = request.app.state.store
    if (
        not request.app.state.settings.session_summary_enabled
        or store is None
        or store.summaries is None
    ):
        raise PersistenceError("SUMMARY_UNAVAILABLE", "会话摘要功能未启用或暂不可用", 503)
    return store.summaries


@router.post("/get")
async def get(payload: SummaryRequest, request: Request):
    return await require_summary(request).get(payload)


@router.post("/settings")
async def settings(payload: SummarySettings, request: Request):
    return await require_summary(request).settings(payload)


@router.post("/clear")
async def clear(payload: SummaryClear, request: Request):
    return await require_summary(request).clear(payload)
