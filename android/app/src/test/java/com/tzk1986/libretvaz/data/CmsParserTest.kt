package com.tzk1986.libretvaz.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsParserTest {
    // vod_play_url 的分隔符 "$"：避免 Kotlin 字符串模板，用 unicode 转义
    private val d = "$"

    @Test
    fun `extractEpisodes prefers group containing m3u8`() {
        // 与 Web 端一致：vod_play_url 内不含线路名（线路名在 vod_play_from 字段）
        val playUrl = "share页${d}https://x.com/share/abc#第2集${d}https://x.com/share/def" +
            "$d$d$d" + "第1集${d}https://a.com/1.m3u8#第2集${d}https://a.com/2.m3u8"
        val eps = CmsParser.extractEpisodesFromPlayUrl(playUrl)
        assertEquals(listOf("https://a.com/1.m3u8", "https://a.com/2.m3u8"), eps)
    }

    @Test
    fun `extractEpisodes falls back to first group without m3u8`() {
        val playUrl = "线1${d}https://a.com/1.mp4#集2${d}https://a.com/2.mp4"
        val eps = CmsParser.extractEpisodesFromPlayUrl(playUrl)
        assertEquals(listOf("https://a.com/1.mp4", "https://a.com/2.mp4"), eps)
    }

    @Test
    fun `extractM3u8FromText strips leading dollar`() {
        val text = "播单：${d}https://a.com/p/1.m3u8 和 ${d}https://a.com/p/2.m3u8"
        val eps = CmsParser.extractM3u8FromText(text)
        assertEquals(listOf("https://a.com/p/1.m3u8", "https://a.com/p/2.m3u8"), eps)
    }

    @Test
    fun `normalizeTitle removes punctuation and lowercases`() {
        assertEquals("摔跤吧爸爸", CmsParser.normalizeTitle("摔跤吧!爸爸"))
        assertEquals("abc", CmsParser.normalizeTitle(" A.B·C "))
    }

    @Test
    fun `isRelevant matches across punctuation and multi tokens`() {
        assertTrue(CmsParser.isRelevant("摔跤吧！爸爸", "摔跤吧 爸爸"))
        assertTrue(CmsParser.isRelevant("庆余年 第二季", "庆余年2"))
        assertTrue(CmsParser.isRelevant("庆余年第三季", "庆余年 3"))
        assertTrue(CmsParser.isRelevant("我是大侦探第二季粤语版", "我是大侦探2"))
        assertFalse(CmsParser.isRelevant("庆余年12", "庆余年1"))
        assertFalse(CmsParser.isRelevant("爸爸去哪儿", "摔跤吧!爸爸"))
        assertFalse(CmsParser.isRelevant("庆余年 第二季", "庆余年3"))
    }

    @Test
    fun `parseSearchList maps core fields`() {
        val json = JSONObject(
            """{"list":[{"vod_id":"12","vod_name":"测试片","vod_pic":"http://p/1.jpg",
            "type_name":"动漫","vod_year":"2024","vod_area":"中国","vod_remarks":"更新至3集"}]}"""
        )
        val list = CmsParser.parseSearchList(json, VodSource("源A", "https://a.com/api.php/provide/vod"))
        assertEquals(1, list.size)
        assertEquals("12", list[0].vodId)
        assertEquals("测试片", list[0].name)
        assertEquals("源A", list[0].sourceName)
    }

    @Test
    fun `parseDetail returns episodes and falls back to content`() {
        val source = VodSource("源A", "https://a.com/api.php/provide/vod")
        val viaPlayUrl = JSONObject("""{"list":[{"vod_name":"x","vod_play_url":"第1集${d}https://a/1.m3u8"}]}""")
        assertEquals(listOf("https://a/1.m3u8"), CmsParser.parseDetail(viaPlayUrl, source)?.episodes)

        val viaContent = JSONObject("""{"list":[{"vod_name":"x","vod_play_url":"","vod_content":"剧：${d}https://a/2.m3u8"}]}""")
        assertEquals(listOf("https://a/2.m3u8"), CmsParser.parseDetail(viaContent, source)?.episodes)
    }

    @Test
    fun `adult type detection`() {
        assertTrue(CmsParser.isAdultType("伦理片"))
        assertFalse(CmsParser.isAdultType("喜剧"))
    }
}
