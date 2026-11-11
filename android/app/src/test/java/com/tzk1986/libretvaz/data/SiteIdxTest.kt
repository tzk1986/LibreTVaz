package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 审计 2026-11-11 P0-1：siteIdx 全局统一为点播池下标（vodPool=非专区）。
// 全量列表（vodSources 含专区）与池下标的边界换算纯函数单测。
class SiteIdxTest {
    private fun s(adult: Boolean, n: String) = VodSource(n, "https://$n.example/api", null, adult)
    // [0]=普通a [1]=专区z [2]=普通b [3]=普通c → 池=[a,b,c] 下标 0,1,2
    private val all = listOf(s(false, "a"), s(true, "z"), s(false, "b"), s(false, "c"))

    @Test
    fun `fullToPool maps non-adult rows`() {
        assertEquals(0, SiteIdx.fullToPool(all, 0))
        assertEquals(1, SiteIdx.fullToPool(all, 2))
        assertEquals(2, SiteIdx.fullToPool(all, 3))
    }

    @Test
    fun `fullToPool null for adult row and oob`() {
        assertNull(SiteIdx.fullToPool(all, 1))
        assertNull(SiteIdx.fullToPool(all, 4))
        assertNull(SiteIdx.fullToPool(all, -1))
        assertNull(SiteIdx.fullToPool(emptyList<VodSource>(), 0))
    }

    @Test
    fun `poolToFull is inverse of fullToPool`() {
        assertEquals(0, SiteIdx.poolToFull(all, 0))
        assertEquals(2, SiteIdx.poolToFull(all, 1))
        assertEquals(3, SiteIdx.poolToFull(all, 2))
        assertEquals(-1, SiteIdx.poolToFull(all, 3)) // 池越界
        assertEquals(-1, SiteIdx.poolToFull(all, -1))
        assertEquals(-1, SiteIdx.poolToFull(listOf(s(true, "z")), 0)) // 全专区无池
    }
}
