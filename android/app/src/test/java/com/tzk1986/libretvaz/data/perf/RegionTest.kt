package com.tzk1986.libretvaz.data.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地区过滤中间模块单测（自 PerfCoreTest 迁出，2026-11-04；优先级 area > typeName > type/clazz） */
class RegionTest {

    @Test fun `classify 地区判定`() {
        assertEquals("DOM", Region.classify(null, "港剧"))
        // area 权威：分类名「美剧」不再压过 vod_area=大陆
        assertEquals("DOM", Region.classify("美剧", "大陆"))
        assertEquals("EUUS", Region.classify(null, "美国"))
        assertEquals("KR", Region.classify(null, null, "韩国综艺"))
        assertEquals("UNKNOWN", Region.classify(null, null, null))
        assertEquals("EUUS", Region.classify(null, "美"))
        // 回归：泰国 area + 国产分类名 → OTHER（修复混进国内的漏洞）
        assertEquals("OTHER", Region.classify("国产电视剧", "泰国"))
        assertEquals("DOM", Region.classify(null, "中国大陆"))
    }

    @Test fun `ofArea 拆词精确优先`() {
        assertEquals("OTHER", Region.ofArea("其它"))
        assertEquals("JP", Region.ofArea("日本，美国"))
        assertNull(Region.ofArea("未知领域"))
    }

    @Test fun `labels 多标签`() {
        assertEquals(listOf("JP", "KR"), Region.labels("日韩综艺"))
        assertEquals(emptyList<String>(), Region.labels("爱情"))
    }

    @Test fun `PRESETS 预设语义`() {
        assertEquals(listOf("EUUS", "JP", "KR", "OTHER"), Region.PRESETS["电影"]!!.first { it.first == "国外" }.second)
        assertEquals(listOf("OTHER"), Region.PRESETS["电视剧"]!!.first { it.first == "小语种" }.second)
        assertTrue(Region.PRESETS["电影"]!!.first { it.first == "全部" }.second.isEmpty())
        assertNotNull(Region.PRESETS["动漫"])
    }
}
