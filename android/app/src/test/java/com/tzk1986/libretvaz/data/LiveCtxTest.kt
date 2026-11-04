package com.tzk1986.libretvaz.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 直播组 2-5 纯函数：换台池（组/收藏过滤）与组内环绕步进（web chanStep 等价） */
class LiveCtxTest {

    @After
    fun tearDown() {
        LiveCtx.chans = emptyList()
        LiveCtx.group = null
        LiveCtx.favOnly = false
        LiveCtx.favUrls = emptySet()
        LiveCtx.cur = -1
    }

    private fun ch(name: String, group: String? = null) = LiveChannel(name, name, "http://$name", group = group)

    @Test
    fun `visible filters by group and fav pool`() {
        LiveCtx.chans = listOf(ch("a", "央"), ch("b", "卫"), ch("c", "央"))
        LiveCtx.group = null
        assertEquals(listOf(0, 1, 2), LiveCtx.visible())
        LiveCtx.group = "央"
        assertEquals(listOf(0, 2), LiveCtx.visible())
        LiveCtx.group = null
        LiveCtx.favOnly = true
        LiveCtx.favUrls = setOf("http://b")
        assertEquals(listOf(1), LiveCtx.visible())
    }

    @Test
    fun `step wraps inside group pool like web chanStep`() {
        LiveCtx.chans = listOf(ch("a", "央"), ch("b", "卫"), ch("c", "央"), ch("d", "央"))
        LiveCtx.group = "央" // 池=[0,2,3]
        LiveCtx.cur = 0
        assertEquals(2, LiveCtx.step(1))
        assertEquals(3, LiveCtx.step(-1)) // 环绕：池首位再上=池末位
        LiveCtx.cur = 3
        assertEquals(0, LiveCtx.step(1)) // 池末位再下=池首位
    }

    @Test
    fun `step from cur outside pool enters pool head`() {
        // web 同式 pos=-1：(pos+d+len)%len 双向都落池首
        LiveCtx.chans = listOf(ch("a", "央"), ch("b", "卫"), ch("c", "央"))
        LiveCtx.group = "央"
        LiveCtx.cur = 1
        assertEquals(0, LiveCtx.step(1))
        assertEquals(0, LiveCtx.step(-1))
    }

    @Test
    fun `same-name duplicates merge to first line in pool`() {
        // B16 精筛表同名多线（速度降序）：换台池只留首条，linesOf 给出全部线路
        LiveCtx.chans = listOf(ch("a"), ch("a"), ch("b"), ch("a"))
        assertEquals(listOf(0, 2), LiveCtx.visible())
        assertEquals(listOf(0, 1, 3), LiveCtx.linesOf(0))
        assertEquals(listOf(2), LiveCtx.linesOf(2))
        LiveCtx.cur = 0
        assertEquals(2, LiveCtx.step(1)) // ↑↓ 跳过备用线直达下一台
    }

    @Test
    fun `step null when pool empty`() {
        LiveCtx.chans = listOf(ch("a", "央"))
        LiveCtx.group = "不存在"
        assertNull(LiveCtx.step(1))
        LiveCtx.chans = emptyList()
        LiveCtx.group = null
        assertNull(LiveCtx.step(1))
    }
}
