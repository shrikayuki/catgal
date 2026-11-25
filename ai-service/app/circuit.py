# 阅读说明
# 模型调用熔断器，连续失败进入冷却，再以受限探测判断是否恢复。

import time
from collections.abc import Callable

from app.errors import ModelCircuitOpenError


class ModelCircuit:
    """A process-local cooldown with one recovery probe per open interval."""

    def __init__(
        self, threshold: int, recovery_seconds: float, now: Callable[[], float] = time.monotonic
    ) -> None:
        self._threshold = threshold
        self._recovery_seconds = recovery_seconds
        self._now = now
        self._failures = 0
        self._open_until = 0.0
        self._generation = 0
        self._probe_active = False

    def enter(self) -> int:
        if self._open_until:
            if self._now() < self._open_until or self._probe_active:
                raise ModelCircuitOpenError()
            self._probe_active = True
        return self._generation

    def success(self, generation: int) -> None:
        if generation != self._generation:
            return
        self._failures = 0
        self._open_until = 0.0
        self._probe_active = False

    def failure(self, generation: int) -> None:
        if generation != self._generation:
            return
        self._failures += 1
        if self._failures >= self._threshold:
            self._open_until = self._now() + self._recovery_seconds
            self._generation += 1
            self._probe_active = False

    def abandon(self, generation: int) -> None:
        if generation == self._generation:
            self._probe_active = False
