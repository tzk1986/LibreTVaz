package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 用户 2026-11-09 复刻 TVBox tvSlideStart/tvSlideStop（VodController 源码 1180-1217 行逆向取证）：
// 首步 ±10s；距上次 ≥500ms 每步 offset=offset*1.5+dir*15s 加速；<500ms 重置基础 ±10s；
// 滑动期间只算预览位，松手（stop 前外部读 simPos 真 seek）
class SlideSeekTest {
    @Test
    fun `first step is ten seconds in dir`() {
        val s = SlideSeek()
        assertTrue(s.step(nowMs = 0, dir = 1, basePosMs = 60_000, durMs = 600_000))
        assertEquals(70_000L, s.simPos)
        assertEquals(1, s.dir)
        assertTrue(s.active)
        val s2 = SlideSeek()
        s2.step(0, -1, 60_000, 600_000)
        assertEquals(50_000L, s2.simPos)
    }

    @Test
    fun `gaps of 500ms or more accelerate offset`() {
        val s = SlideSeek()
        s.step(0, 1, 60_000, 6_000_000)      // +10s
        s.step(500, 1, 60_000, 6_000_000)    // 10*1.5+15=30s
        assertEquals(90_000L, s.simPos)
        s.step(1000, 1, 60_000, 6_000_000)   // 30*1.5+15=60s
        assertEquals(120_000L, s.simPos)
        s.step(1500, 1, 60_000, 6_000_000)   // 60*1.5+15=105s
        assertEquals(165_000L, s.simPos)
    }

    @Test
    fun `fast repeats below 500ms reset to base step`() {
        val s = SlideSeek()
        s.step(0, 1, 60_000, 6_000_000)
        s.step(500, 1, 60_000, 6_000_000)    // 30s
        s.step(560, 1, 60_000, 6_000_000)    // <500ms → 重置 ±10s
        assertEquals(70_000L, s.simPos)
    }

    @Test
    fun `direction flip re-signs naturally through zero`() {
        val s = SlideSeek()
        s.step(0, 1, 60_000, 6_000_000)      // offset=+10s
        s.step(500, -1, 60_000, 6_000_000)   // 10*1.5-15=0s → simPos=base
        assertEquals(60_000L, s.simPos)
        s.step(1000, -1, 60_000, 6_000_000)  // 0*1.5-15=-15s
        assertEquals(45_000L, s.simPos)
        assertEquals(-1, s.dir)
    }

    @Test
    fun `simPos clamps to zero and duration`() {
        val s = SlideSeek()
        s.step(0, -1, 5_000, 600_000)
        assertEquals(0L, s.simPos)
        val s2 = SlideSeek()
        s2.step(0, 1, 595_000, 600_000)
        assertEquals(600_000L, s2.simPos)
    }

    @Test
    fun `no duration means no slide`() {
        val s = SlideSeek()
        assertFalse(s.step(0, 1, 30_000, 0))
        assertFalse(s.active)
    }

    @Test
    fun `stop resets state`() {
        val s = SlideSeek()
        s.step(0, 1, 60_000, 600_000)
        s.stop()
        assertFalse(s.active)
        assertEquals(0, s.dir)
        // 重新按下=新会话首步 ±10s（不带旧加速）
        s.step(700, 1, 60_000, 600_000)
        assertEquals(70_000L, s.simPos)
    }
}
