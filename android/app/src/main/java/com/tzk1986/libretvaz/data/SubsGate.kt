package com.tzk1986.libretvaz.data

/**
 * 维护周期闸门（审计 2026-11-11 P1-3）：时间戳「成功才写满周期」。
 * 旧逻辑先写戳再干活，弱网下整轮订阅重拉/直播探活失败也消费 24h，源池长期陈旧；
 * 失败改落「退避戳」（now-满周期+BACKOFF），到期判定不用改，30 分钟后自然重试。
 */
object SubsGate {
    const val FULL_MS = 24 * 3600_000L
    const val BACKOFF_MS = 30 * 60_000L

    fun due(now: Long, last: Long): Boolean = now - last >= FULL_MS

    fun stamp(now: Long, ok: Boolean): Long = if (ok) now else now - FULL_MS + BACKOFF_MS
}
