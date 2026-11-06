package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// 用户 2026-11-06 搜索墙跨页记忆：key=kw+zone+指定源，命中才还原（对齐聚合墙 browseKeep 模式）
class CollectKeyTest {
    @Test
    fun `same params give same key`() {
        assertEquals(
            collectKey("美人鱼", false, null),
            collectKey("美人鱼", false, null),
        )
    }

    @Test
    fun `kw zone and srcSel each change the key`() {
        val base = collectKey("美人鱼", false, null)
        assertNotEquals(base, collectKey("美人鱼2", false, null))
        assertNotEquals(base, collectKey("美人鱼", true, null))
        assertNotEquals(base, collectKey("美人鱼", false, SpecSrc(3, "s")))
        assertNotEquals(collectKey("美人鱼", false, SpecSrc(3, "s")), collectKey("美人鱼", false, SpecSrc(4, "s")))
    }
}
