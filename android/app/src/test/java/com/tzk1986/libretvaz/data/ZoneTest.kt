package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资源口专用过滤表（客户端分类/筛选）匹配语义测试 */
class ZoneTest {

    private fun item(name: String, typeName: String? = null) =
        VodSummary("k", "src", "http://s", "1", name, typeName = typeName)

    @Test
    fun `有码与无码互不误伤`() {
        val wuma = Zone.ZONE_CATS[1]
        val youma = Zone.ZONE_CATS[0]
        assertTrue(Zone.matchesTag(item("东京无码馆", "日本无码"), wuma))
        assertFalse(Zone.matchesTag(item("东京无码馆", "日本无码"), youma))
        assertTrue(Zone.matchesTag(item("高清有码合集", "有码高清"), youma))
        assertFalse(Zone.matchesTag(item("高清有码合集", "有码高清"), wuma))
    }

    @Test
    fun `分类名缺失时片名兜底命中`() {
        val renqi = Zone.ZONE_FILTERS[0]
        assertTrue(Zone.matchesTag(item("人妻的秘密", null), renqi))
        assertTrue(Zone.matchesTag(item("某节目", "人妻系列"), renqi))
        assertFalse(Zone.matchesTag(item("普通爱情片", "剧情"), renqi))
    }

    @Test
    fun `关键词忽略大小写`() {
        val swag = Zone.ZONE_FILTERS.first { it.key == "自拍国产" }
        assertTrue(Zone.matchesTag(item("Swag 网红主播", "福利视频"), swag))
        assertTrue(Zone.matchesTag(item("SWAG 实拍", null), swag))
    }

    @Test
    fun `动漫命中动画与番且不误伤常规`() {
        val anime = Zone.ZONE_CATS[2]
        assertTrue(Zone.matchesTag(item("国漫新番", "动漫"), anime))
        assertTrue(Zone.matchesTag(item("里番动漫合集", null), anime))
        assertFalse(Zone.matchesTag(item("制服诱惑之夜", "伦理片"), anime))
    }

    @Test
    fun `filterPool 一级与二级取交集`() {
        val pool = listOf(
            item("无码动画特辑", "动漫"), // 动漫∩无码
            item("无码真人", "日本无码"), // 无码
            item("人妻动画", "里番动漫"), // 动漫∩人妻
            item("普通伦理", "伦理片"),
        )
        val wuma = Zone.ZONE_CATS.indexOfFirst { it.key == "无码" }
        val anime = Zone.ZONE_CATS.indexOfFirst { it.key == "动漫" }
        val renqi = Zone.ZONE_FILTERS.indexOfFirst { it.key == "人妻" }
        assertEquals(2, Zone.filterPool(pool, wuma, -1).size) // 一级=无码
        assertEquals(2, Zone.filterPool(pool, anime, -1).size) // 一级=动漫
        assertEquals(1, Zone.filterPool(pool, -1, renqi).size) // 二级=人妻
        assertEquals(listOf("人妻动画"), Zone.filterPool(pool, anime, renqi).map { it.name }) // 动漫∩人妻
        assertSame(pool, Zone.filterPool(pool, -1, -1))
    }

    @Test
    fun `越界下标视为不限`() {
        val pool = listOf(item("任意", null))
        assertSame(pool, Zone.filterPool(pool, 99, 99))
    }
}
