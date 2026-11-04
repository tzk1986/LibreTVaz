package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B16 国家分类纯函数：tvg-id 尾段国家码提取与六分类映射、展示序分组 */
class LiveGeoTest {

    @Test
    fun `countryCode extracts from tvg-id tail`() {
        assertEquals("cn", LiveGeo.countryCode("CCTV1.cn@SD"))
        assertEquals("jp", LiveGeo.countryCode("NHK.jp"))
        assertNull(LiveGeo.countryCode(null))
        assertNull(LiveGeo.countryCode("no-dot-here"))
        assertNull(LiveGeo.countryCode("name.xyz@SD")) // 3 字母不合规则
    }

    @Test
    fun `catOf maps codes to six categories`() {
        assertEquals("中国", LiveGeo.catOf("x.cn@HD"))
        assertEquals("日本", LiveGeo.catOf("x.jp"))
        assertEquals("韩国", LiveGeo.catOf("x.kr"))
        assertEquals("美国", LiveGeo.catOf("x.us"))
        assertEquals("欧洲", LiveGeo.catOf("x.de"))
        assertEquals("欧洲", LiveGeo.catOf("x.gb@HD"))
        assertEquals("其他", LiveGeo.catOf("x.br"))
        assertEquals("其他", LiveGeo.catOf(null))
    }

    @Test
    fun `partition keeps display order and drops empty cats`() {
        val ch = { id: String? -> LiveChannel("n", "n", "http://u", tvgId = id) }
        val parts = LiveGeo.partition(listOf(ch("x.us"), ch("x.cn"), ch("x.us"), ch(null)))
        assertEquals(listOf("中国", "美国", "其他"), parts.map { it.first })
        assertEquals(1, parts[0].second.size)
        assertEquals(2, parts[1].second.size)
    }
}
