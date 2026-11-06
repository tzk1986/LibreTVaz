package com.tzk1986.libretvaz.data

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsParserTest {
    // vod_play_url 的分隔符 "$"：避免 Kotlin 字符串模板，用 unicode 转义
    private val d = "$"

    @Test
    fun `extractLines keeps all groups and flags from play_from`() {
        val playUrl = "share页${d}https://x.com/share/abc#第2集${d}https://x.com/share/def" +
            "$d$d$d" + "第1集${d}https://a.com/1.m3u8#第2集${d}https://a.com/2.m3u8"
        val lines = CmsParser.extractLinesFromPlayUrl(playUrl, "分享$d$d${d}官源")
        assertEquals(2, lines.size)
        assertEquals("分享", lines[0].flag)
        assertEquals("官源", lines[1].flag)
        assertEquals(listOf("第1集", "第2集"), lines[1].list.map { it.name })
        assertEquals(listOf("https://a.com/1.m3u8", "https://a.com/2.m3u8"), lines[1].list.map { it.link })
    }

    @Test
    fun `extractLines falls back to inline flag prefix then default`() {
        val inline = "极速:第1集${d}https://a.com/1.mp4"
        assertEquals("极速", CmsParser.extractLinesFromPlayUrl(inline)[0].flag)
        val plain = "第1集${d}https://a.com/1.mp4#集2${d}https://a.com/2.mp4"
        val lines = CmsParser.extractLinesFromPlayUrl(plain)
        assertEquals("默认线路", lines[0].flag)
        assertEquals(2, lines[0].list.size)
    }

    @Test
    fun `pickDefaultLine prefers stream suffix group`() {
        val lines = listOf(
            PlayLine("l0", listOf(PlayEpisode("s", "https://x.com/share/abc"))),
            PlayLine("l1", listOf(PlayEpisode("1", "https://a.com/1.m3u8"))),
        )
        assertEquals(1, Playback.pickDefaultLine(lines))
        assertEquals(-1, Playback.pickDefaultLine(emptyList()))
    }

    @Test
    fun `pickNextLine excludes tried and prefers stream links`() {
        val lines = listOf(
            PlayLine("a", listOf(PlayEpisode("1", "https://a/1.html"))),
            PlayLine("b", listOf(PlayEpisode("1", "https://b/1.m3u8"))),
            PlayLine("c", listOf(PlayEpisode("1", "https://c/1.mp4"))),
        )
        val cand = Playback.pickNextLine(lines, line = 0, curEp = 0, tried = setOf(2))
        assertEquals(listOf(1), cand.map { it.index }) // 2 已试过被排除，0 是当前
    }

    @Test
    fun `extractM3u8FromText strips leading dollar`() {
        val text = "播单：${d}https://a.com/p/1.m3u8 和 ${d}https://a.com/p/2.m3u8"
        val lines = CmsParser.extractM3u8LinesFromText(text)
        assertEquals(listOf("https://a.com/p/1.m3u8", "https://a.com/p/2.m3u8"), lines[0].list.map { it.link })
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
        assertFalse(CmsParser.isRelevant("庆余年12", "庆余年1", fuzzy = false))
        assertFalse(CmsParser.isRelevant("爸爸去哪儿", "摔跤吧!爸爸"))
        assertFalse(CmsParser.isRelevant("庆余年 第二季", "庆余年3", fuzzy = false))
    }

    @Test
    fun `fuzzy core-name fallback rescues season-mismatched titles`() {
        // 实验室「模糊搜索」开（默认）：核心片名互含也算命中（web fongmi_fuzzy 等价）
        assertTrue(CmsParser.isRelevant("庆余年12", "庆余年1"))
        assertTrue(CmsParser.isRelevant("庆余年 第二季", "庆余年3"))
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
    fun `parseDetail returns lines and falls back to content`() {
        val source = VodSource("源A", "https://a.com/api.php/provide/vod")
        val viaPlayUrl = JSONObject("""{"list":[{"vod_name":"x","vod_play_url":"第1集${d}https://a/1.m3u8"}]}""")
        assertEquals(listOf("https://a/1.m3u8"), CmsParser.parseDetail(viaPlayUrl, source)?.lines?.first()?.list?.map { it.link })

        val viaContent = JSONObject("""{"list":[{"vod_name":"x","vod_play_url":"","vod_content":"剧：${d}https://a/2.m3u8"}]}""")
        assertEquals(listOf("https://a/2.m3u8"), CmsParser.parseDetail(viaContent, source)?.lines?.first()?.list?.map { it.link })
    }

    @Test
    fun `parseTypes reads class array and skips incomplete entries`() {
        val json = JSONObject(
            """{"class":[{"type_id":"1","type_name":"电影"},{"type_id":"2","type_name":""},{"type_name":"无id"}]}"""
        )
        val types = CmsParser.parseTypes(json)
        assertEquals(1, types.size)
        assertEquals("1", types[0].id)
        assertEquals("电影", types[0].name)
        assertTrue(CmsParser.parseTypes(JSONObject("""{"code":200}""")).isEmpty())
    }

    @Test
    fun `parseExtend handles object json-string and semicolon forms`() {
        // web parseExtend 三形态：JSON 对象 / JSON 串 / "area,内地,港台;year,2026" 分号逗号串
        val obj = CmsParser.parseExtend(JSONObject("""{"area":["内地","港台"],"year":"2026,2025"}"""))
        assertEquals("内地,港台", obj["area"])
        val sem = CmsParser.parseExtend("area,内地,港台;year,2026,2025")
        assertEquals("内地,港台", sem["area"])
        assertEquals("2026,2025", sem["year"])
        assertTrue(CmsParser.parseExtend(null).isEmpty())
        assertTrue(CmsParser.parseExtend("").isEmpty())
    }

    @Test
    fun `parseTypes carries extend and parseFilterMeta picks first class with filters`() {
        val json = JSONObject("""{"class":[{"type_id":"1","type_name":"电影","type_extend":"area,内地,港台;year,2026"},{"type_id":"2","type_name":"剧"}]}""")
        val types = CmsParser.parseTypes(json)
        assertEquals("内地,港台", types[0].extend["area"])
        assertTrue(types[1].extend.isEmpty())
        val meta = CmsParser.parseFilterMeta(json)
        assertEquals("2026", meta!!["year"])
        assertNull(CmsParser.parseFilterMeta(JSONObject("""{"class":[{"type_id":"1","type_name":"x"}]}""")))
    }

    @Test
    fun `adult type detection`() {
        assertTrue(CmsParser.isAdultType("伦理片"))
        assertFalse(CmsParser.isAdultType("喜剧"))
    }

    // 用户 2026-11-06 海报墙：季数前置到更新集数徽章前——从片名提取「第X季」纯函数
    @Test
    fun `seasonOf extracts last season marker from title`() {
        assertEquals("第二季", CmsParser.seasonOf("庆余年 第二季"))
        assertEquals("第3季", CmsParser.seasonOf("老子英雄铁女儿 第3季"))
        assertEquals("第2季", CmsParser.seasonOf("某某 第1季 第2季")) // 多季并排取最后
        assertNull(CmsParser.seasonOf("妈妈发疯了"))
        assertNull(CmsParser.seasonOf("2季 不算")) // 缺「第」不算
    }

    // 用户 2026-11-06 海报墙截断优化 A+B：显示名先裁「第X季」（徽章已承载）与结尾年份，仍长再换行
    @Test
    fun `displayTitle strips season marker and trailing year`() {
        assertEquals("庆余年", CmsParser.displayTitle("庆余年 第二季"))
        assertEquals("爱情保卫战", CmsParser.displayTitle("爱情保卫战2026"))
        assertEquals("爱情保卫战", CmsParser.displayTitle("爱情保卫战 - 2026"))
        assertEquals("乔治和曼迪的头婚生活", CmsParser.displayTitle("乔治和曼迪的头婚生活第三季 2026"))
        assertEquals("老子英雄铁女儿", CmsParser.displayTitle("老子英雄铁女儿 第3季"))
    }

    @Test
    fun `displayTitle keeps plain and degenerate names`() {
        assertEquals("凡人修仙传", CmsParser.displayTitle("凡人修仙传"))
        assertEquals("2026", CmsParser.displayTitle("2026")) // 裁空则回退原名
        assertEquals("第3季", CmsParser.displayTitle("第3季"))
    }
}
