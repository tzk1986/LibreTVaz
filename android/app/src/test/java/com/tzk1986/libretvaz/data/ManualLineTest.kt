package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方案1（用户 2026-11-12 裁决）：手动换线持久化——重进详情选线优先级纯逻辑。
 * 优先级：manual（用户最后一次手动点线）> 进度 ph（按线名→序数）> best（测速最优）。
 * manual 线名不在线路表（源改名/线被撤）=弃权回落，不钳不猜。
 */
class ManualLineTest {

    @Test
    fun manualHit_winsOverProgAndBest() {
        assertEquals(0, resolveEntryLine(listOf("feifan", "ffm3u8"), best = 1, manualFlag = "feifan", progFlag = "ffm3u8", progLine = 1))
    }

    @Test
    fun manualMissing_fallsBackToProgFlag() {
        assertEquals(1, resolveEntryLine(listOf("x", "ffm3u8"), best = 0, manualFlag = "feifan", progFlag = "ffm3u8", progLine = 0))
    }

    @Test
    fun noManual_progFlagMatches_usesFlagIndex() {
        assertEquals(2, resolveEntryLine(listOf("a", "b", "c"), best = 0, manualFlag = null, progFlag = "c", progLine = 0))
    }

    @Test
    fun noManual_progFlagEmpty_usesProgLineClamped() {
        assertEquals(1, resolveEntryLine(listOf("a", "b"), best = 0, manualFlag = "", progFlag = "", progLine = 5))
    }

    @Test
    fun noManualNoProg_usesBest() {
        assertEquals(1, resolveEntryLine(listOf("a", "b", "c"), best = 1, manualFlag = null, progFlag = null, progLine = -1))
    }

    @Test
    fun emptyFlags_bestNegative_returnsNegative() {
        // 空线路表：全弃权返 -1，由调用方 coerce 兜底（同旧 pickDefaultLine 语义）
        assertEquals(-1, resolveEntryLine(emptyList(), best = 0, manualFlag = "a", progFlag = "a", progLine = 0))
    }

    @Test
    fun storeKey_isVodIdPipeSource() {
        assertEquals("123|http://s/api", ManualLine.key("123", "http://s/api"))
    }
}
