"""百炼 embedding 全局速率限制器。

避免高并发瞬时突发触发账号级限流(429)。连接复用让请求发得又快又密，
单纯靠并发数控制不住瞬时速率，需在客户端主动限速。

实测(2026-06)：恒定 18 req/s(~1080 RPM)安全，24 req/s(~1440 RPM)触发 429，
故取 15 req/s 留安全余量。text/image embedding 共享同一账号配额，共用一个限制器。
"""

import asyncio
import time
from typing import Optional


class AsyncRateLimiter:
    """异步令牌桶：平均放行速率 = rate，允许至多 burst 的瞬时突发。"""

    def __init__(self, rate: float, burst: Optional[float] = None):
        self._rate = rate
        self._capacity = burst if burst is not None else rate
        self._tokens = self._capacity
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            while True:
                now = time.monotonic()
                self._tokens = min(self._capacity, self._tokens + (now - self._last) * self._rate)
                self._last = now
                if self._tokens >= 1:
                    self._tokens -= 1
                    return
                await asyncio.sleep((1 - self._tokens) / self._rate)


_EMBED_RATE = 15.0   # req/s：实测 18 安全 / 24 限流，取 15 留余量
_EMBED_BURST = 4.0   # 削瞬时峰：避免开局令牌一次放光、并发请求同时砸向 API 触发 429

_limiter: Optional[AsyncRateLimiter] = None


def get_embedding_rate_limiter() -> AsyncRateLimiter:
    """全局共享的 embedding 限速器（text/image 同账号配额，共用）。"""
    global _limiter
    if _limiter is None:
        _limiter = AsyncRateLimiter(_EMBED_RATE, _EMBED_BURST)
    return _limiter
