# 阅读说明
# 流式请求的并发槽位与 SSE 编码，生成方用事件类型表达生命周期。

import asyncio
import json


class ConcurrencyGate:
    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._active = 0
        self._lock = asyncio.Lock()

    async def try_acquire(self) -> bool:
        async with self._lock:
            if self._active >= self._limit:
                return False
            self._active += 1
            return True

    async def release(self) -> None:
        async with self._lock:
            self._active = max(0, self._active - 1)


def sse_event(event: str, data: dict[str, object]) -> bytes:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {payload}\n\n".encode()
