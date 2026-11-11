package com.tzk1986.libretvaz.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计 2026-11-11 P1-3：订阅/直播维护周期闸门——时间戳「成功才写满 24h，失败写短退避」防网络差时整轮失败仍消费周期、源池长期陈旧 */
class SubsGateTest {

    private val full = SubsGate.FULL_MS
    private val backoff = SubsGate.BACKOFF_MS

    @Test
    fun `due fires at full period and never before`() {
        val last = 1_000_000L
        assertFalse(SubsGate.due(last + full - 1, last))
        assertTrue(SubsGate.due(last + full, last))
        assertTrue(SubsGate.due(last + full + 5, last))
    }

    @Test
    fun `due handles first run (epoch 0)`() {
        assertTrue(SubsGate.due(System.currentTimeMillis(), 0L))
    }

    @Test
    fun `success stamps full period`() {
        val now = 2_000_000L
        val ts = SubsGate.stamp(now, ok = true)
        assertFalse(SubsGate.due(now + full - 1, ts))
        assertTrue(SubsGate.due(now + full, ts))
    }

    @Test
    fun `failure stamps short backoff not full period`() {
        val now = 3_000_000L
        val ts = SubsGate.stamp(now, ok = false)
        // 退避期内不再重试
        assertFalse(SubsGate.due(now + backoff - 1, ts))
        // 退避一到即重新到期，无需等满 24h
        assertTrue(SubsGate.due(now + backoff, ts))
    }

    @Test
    fun `backoff is strictly shorter than full period`() {
        assertTrue(backoff in 1 until full)
    }
}
