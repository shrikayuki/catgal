# 阅读说明
# 私有记忆向量召回授权的内部设置路由。

from fastapi import APIRouter, Depends, Request

from app.memory_vector.contracts import MemoryVectorRequest, MemoryVectorUpdate
from app.persistence.contracts import PersistenceError
from app.security import verify_internal_request

router = APIRouter(
    prefix="/internal/v1/memory-vector", dependencies=[Depends(verify_internal_request)]
)


def require_vectors(request):
    store = request.app.state.store
    if (
        not request.app.state.settings.memory_vector_enabled
        or store is None
        or store.memory_vectors is None
    ):
        raise PersistenceError("MEMORY_VECTOR_UNAVAILABLE", "私有记忆向量功能未启用或暂不可用", 503)
    return store.memory_vectors


@router.post("/settings/get")
async def settings(payload: MemoryVectorRequest, request: Request):
    return await require_vectors(request).settings(payload)


@router.post("/settings/update")
async def update_settings(payload: MemoryVectorUpdate, request: Request):
    return await require_vectors(request).update_settings(payload)
