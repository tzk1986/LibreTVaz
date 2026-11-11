package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 审计 2026-11-11 P1-6 字幕轨轮切纯逻辑：-1=关闭，循环 -1→0→…→n-1→-1；标签 label>language>「轨n」 */
class SubtitleSelTest {

    @Test
    fun `off cycles to first track`() {
        assertEquals(0, subNext(-1, 2))
    }

    @Test
    fun `selected cycles to next`() {
        assertEquals(1, subNext(0, 3))
    }

    @Test
    fun `last track cycles back to off`() {
        assertEquals(-1, subNext(1, 2))
    }

    @Test
    fun `no tracks stays off`() {
        assertEquals(-1, subNext(-1, 0))
        assertEquals(-1, subNext(0, 0))
    }

    @Test
    fun `label prefers label then language then index fallback`() {
        assertEquals("简体中文", subLabel("简体中文", "chi", 0))
        assertEquals("eng", subLabel(null, "eng", 1))
        assertEquals("轨 3", subLabel("", "", 2))
    }
}
