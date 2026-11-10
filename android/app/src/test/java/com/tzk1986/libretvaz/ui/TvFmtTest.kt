package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// TVBox 复刻（用户 2026-11-09）：全屏顶栏次行「清晰度」按视频高度取友好档名；
// 未知/0 返回空串（调用方据此隐藏该行，不显示"0 x 0 低清"之类噪声）。
class TvFmtTest {
    @Test
    fun `qualityLabel maps height tiers`() {
        assertEquals("4K", qualityLabel(2160))
        assertEquals("4K", qualityLabel(3840))
        assertEquals("超清", qualityLabel(1080))
        assertEquals("超清", qualityLabel(1440))
        assertEquals("高清", qualityLabel(720))
        assertEquals("标清", qualityLabel(480))
        assertEquals("流畅", qualityLabel(360))
    }

    @Test
    fun `qualityLabel blank for unknown height`() {
        assertEquals("", qualityLabel(0))
        assertEquals("", qualityLabel(-1))
    }

    @Test
    fun `倍速面板选项为方案定稿七档`() {
        // TVBox 对标 F1（2026-11-10）：面板 chips 集合钉死，胶囊文案=x+speedLabel
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f), SPEED_OPTIONS.toList())
        assertEquals("x1.5", "x" + speedLabel(1.5f))
    }

    @Test
    fun `speedLabel trims to needed decimals keeping one place`() {
        assertEquals("1.0", speedLabel(1f))
        assertEquals("0.5", speedLabel(0.5f))
        assertEquals("1.25", speedLabel(1.25f))
        assertEquals("1.5", speedLabel(1.5f))
        assertEquals("2.0", speedLabel(2f))
        assertEquals("0.75", speedLabel(0.75f))
    }

    @Test
    fun `fmtSkip pads MM_SS for skip-length label`() {
        assertEquals("01:00", fmtSkip(60))
        assertEquals("00:15", fmtSkip(15))
        assertEquals("02:30", fmtSkip(150))
    }

    @Test
    fun `fmtClock zero-pads 24h hour and minute`() {
        assertEquals("09:05", fmtClock(9, 5))
        assertEquals("14:30", fmtClock(14, 30))
        assertEquals("00:00", fmtClock(0, 0))
    }

    // 用户 2026-11-10 实拍图：右下大时钟带秒（HH:MM:SS 零填充）
    @Test
    fun `fmtClockHms zero-pads hour minute second`() {
        assertEquals("09:05:03", fmtClockHms(9, 5, 3))
        assertEquals("23:59:59", fmtClockHms(23, 59, 59))
        assertEquals("00:00:00", fmtClockHms(0, 0, 0))
    }

    // 详情页倒序（tvSort）：展示序反转但携带原始下标，点选仍寻址真集（tvbox SeriesAdapter sort 分支）
    @Test
    fun `epDisplayOrder keeps real index when forward`() {
        assertEquals(listOf(0 to "a", 1 to "b", 2 to "c"), epDisplayOrder(listOf("a", "b", "c"), reversed = false))
    }

    @Test
    fun `epDisplayOrder reverses display but keeps real index`() {
        assertEquals(listOf(2 to "c", 1 to "b", 0 to "a"), epDisplayOrder(listOf("a", "b", "c"), reversed = true))
    }
}
