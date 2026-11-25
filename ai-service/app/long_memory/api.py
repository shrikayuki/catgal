# 阅读说明
# 已确认长期记忆的设置与 CRUD 路由，归属与来源权限由仓储核验。

from fastapi import APIRouter, Depends, Request

from app.long_memory.contracts import (
    MemoryCreate,
    MemoryDelete,
    MemoryQuery,
    MemorySettingsRequest,
    MemorySettingsUpdate,
    MemoryUpdate,
)
from app.persistence.contracts import PersistenceError
from app.security import verify_internal_request

router = APIRouter(prefix="/internal/v1/memory", dependencies=[Depends(verify_internal_request)])


def require_memory(request):
    store = request.app.state.store
    if not request.app.state.settings.long_term_memory_enabled or store is None:
        raise PersistenceError("LONG_TERM_MEMORY_UNAVAILABLE", "长期记忆功能未启用", 503)
    if store.long_term_memory is None:
        raise PersistenceError("LONG_TERM_MEMORY_UNAVAILABLE", "长期记忆暂不可用", 503)
    return store.long_term_memory


@router.post("/settings/get")
async def settings(payload: MemorySettingsRequest, request: Request):
    return await require_memory(request).settings(payload)


@router.post("/settings/update")
async def update_settings(payload: MemorySettingsUpdate, request: Request):
    return await require_memory(request).update_settings(payload)


@router.post("/list")
async def list_memories(payload: MemoryQuery, request: Request):
    return await require_memory(request).list(payload)


@router.post("/create")
async def create(payload: MemoryCreate, request: Request):
    return await require_memory(request).create(payload)


@router.post("/update")
async def update(payload: MemoryUpdate, request: Request):
    return await require_memory(request).update(payload)


@router.post("/delete")
async def delete(payload: MemoryDelete, request: Request):
    return await require_memory(request).delete(payload)
