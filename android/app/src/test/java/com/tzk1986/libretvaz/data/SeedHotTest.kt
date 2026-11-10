package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TVBox 对标 S1（2026-11-10）：热搜种子榜纯约束测试 */
class SeedHotTest {
    @Test
    fun `榜单恰为40条`() {
        assertEquals(40, SeedHot.LIST.size)
    }

    @Test
    fun `无重复无空白`() {
        assertEquals(SeedHot.LIST.size, SeedHot.LIST.distinct().size)
        assertTrue(SeedHot.LIST.none { it.isBlank() })
    }

    @Test
    fun `种子权重高于新装实测爬词上限`() {
        // 2026-11-10 装机实测：pm clear 后进搜索页，VocabSeed+墙 addWords 把目录词刷到权重 9，
        // 种子仅 +1 被整体挤出 top(40)=热榜全爬词（方案 S1 目标落空）→ 种子权重须 >9
        assertTrue(SeedHot.BUMP > 9L)
    }

    @Test
    fun `版本闸门仅在版本不同时触发`() {
        assertTrue(SeedHot.shouldSeed("", SeedHot.VER))
        assertTrue(SeedHot.shouldSeed("hot0", SeedHot.VER))
        assertEquals(false, SeedHot.shouldSeed(SeedHot.VER, SeedHot.VER))
    }
}
