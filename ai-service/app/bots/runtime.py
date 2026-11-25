# 阅读说明
# 按本次生成配置选择平台运行时或个人模型运行时，使用后释放个人实例。

from contextlib import asynccontextmanager

import anyio

from app.persistence.contracts import PersistenceError


class RuntimeProvider:
    def __init__(self, settings, factory, runtime=None, semantic=None):
        self.settings = settings
        self.factory = factory
        self.platform = runtime
        self.semantic = semantic

    @asynccontextmanager
    async def use(self, profile):
        private = profile is not None and profile.settings is not None
        runtime = None
        try:
            if private:
                runtime = self.factory(profile.settings, None)
            else:
                if self.platform is None:
                    self.platform = self.factory(self.settings, None)
                runtime = self.platform
            if self.semantic is not None and hasattr(runtime, "set_semantic_search"):
                runtime.set_semantic_search(self.semantic)
        except Exception:
            raise PersistenceError("AI_SERVICE_UNAVAILABLE", "模型配置不可用", 503) from None
        try:
            yield runtime
        finally:
            if private:
                with anyio.CancelScope(shield=True):
                    with anyio.move_on_after(3):
                        await self.close_runtime(runtime)

    async def close_runtime(self, runtime):
        close = getattr(runtime, "aclose", None)
        if close is not None:
            await close()

    async def aclose(self):
        await self.close_runtime(self.platform)
