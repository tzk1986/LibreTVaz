package com.tzk1986.libretvaz.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {

    @Test
    fun `tvbox tolerates comments and trailing commas`() {
        val text = """
            // 顶部注释
            {
              "name": "我的配置", /* 块注释 */
              "sites": [
                { "name": "A源", "api": "https://a.com/api.php/provide/vod/", "type": 1 },
                { "name": "B源", "api": "https://b.com/api.php/provide/vod", "type": 1 },
              ],
              "lives": [
                { "name": "直播", "type": 0, "url": "https://live.com/m.m3u8", "epg": "https://epg.com" },
              ],
            }
        """.trimIndent()
        val p = SubscriptionParser.parse(text)
        assertEquals("tvbox", p.format)
        assertEquals("我的配置", p.name)
        assertEquals(listOf("https://a.com/api.php/provide/vod", "https://b.com/api.php/provide/vod"), p.vod.map { it.url })
        assertEquals(1, p.live.size)
        assertEquals("https://epg.com", p.live[0].epg)
    }

    @Test
    fun `tvbox skips spider and non-importable sites`() {
        val text = """{"sites":[
            {"name":"爬虫","api":"csp_xxx","type":3},
            {"name":"jar","api":"https://x.com/f.jar","type":3},
            {"name":"不可搜","api":"https://s.com/api.php/provide/vod","type":1,"searchable":0},
            {"name":"非cms","api":"https://y.com/other","type":4},
            {"name":"cms宽容","api":"https://z.com/api.php/provide/vod/","type":0},
            {"name":"直连","api":"https://w.com/api.php/provide/vod","type":1}
        ]}"""
        val p = SubscriptionParser.parse(text)
        assertEquals(listOf("cms宽容", "直连"), p.vod.map { it.name })
        assertEquals(4, p.skipped)
    }

    @Test
    fun `tvbox live skips txt and nonzero type`() {
        val text = """{"lives":[
            {"name":"ok","type":0,"url":"https://a.com/x.m3u8"},
            {"name":"txt","type":0,"url":"https://b.com/list.txt"},
            {"name":"其它","type":1,"url":"https://c.com/y.m3u8"}
        ]}"""
        val p = SubscriptionParser.parse(text)
        assertEquals(listOf("ok"), p.live.map { it.name })
        assertEquals(2, p.skipped)
    }

    @Test
    fun `no importable source throws`() {
        val text = """{"sites":[{"name":"爬虫","api":"csp_x","type":3}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { SubscriptionParser.parse(text) }
        assertTrue(e.message!!.contains("跳过"))
    }

    @Test
    fun `source catalog format uses baseUrl and honors enabled`() {
        val text = """[
            {"name":"X","baseUrl":"https://x.com/api.php/provide/vod/","enabled":true},
            {"name":"Y","baseUrl":"https://y.com/api.php/provide/vod","enabled":false},
            {"name":"Z","baseUrl":"https://x.com/api.php/provide/vod/"}
        ]"""
        val p = SubscriptionParser.parse(text)
        assertEquals("source-catalog", p.format)
        assertEquals(listOf("https://x.com/api.php/provide/vod"), p.vod.map { it.url })
        assertEquals(1, p.skipped)
    }

    @Test
    fun `mixed catalog and url entries throws`() {
        val text = """[{"baseUrl":"https://x.com/api.php/provide/vod"},{"url":"https://y.com/api.php/provide/vod"}]"""
        assertThrows(IllegalArgumentException::class.java) { SubscriptionParser.parse(text) }
    }

    @Test
    fun `bare array parses as libretv source list`() {
        val text = """[
            {"name":"P","url":"https://p.com/api.php/provide/vod/","detail":"https://p.com/?m=vod-detail-id-","isAdult":true},
            {"name":"dup","url":"https://p.com/api.php/provide/vod/"}
        ]"""
        val p = SubscriptionParser.parse(text)
        assertEquals("libretv", p.format)
        assertEquals(1, p.vod.size)
        assertTrue(p.vod[0].isAdult)
        assertEquals("https://p.com/?m=vod-detail-id-", p.vod[0].detail)
    }

    @Test
    fun `object with sources and liveSources`() {
        val text = """{"name":"S","sources":[{"name":"q","url":"http://q.com/api.php/provide/vod"}],
            "liveSources":[{"name":"l","url":"https://l.com/a.m3u8"}]}"""
        val p = SubscriptionParser.parse(text)
        assertEquals("libretv", p.format)
        assertEquals("S", p.name)
        assertEquals(1, p.vod.size)
        assertEquals(1, p.live.size)
    }

    @Test
    fun `invalid json throws`() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionParser.parse("not json at all {") }
    }

    private val repoA = """{"sites":[{"name":"A1","api":"https://a1.com/api.php/provide/vod","type":1}]}"""
    private val repoB = """{"sites":[{"name":"B1","api":"https://b1.com/api.php/provide/vod","type":1},
        {"name":"Bsp","api":"csp_x","type":3}]}"""
    private val multi = """{"name":"多仓","urls":[
        {"name":"r1","url":"https://repo/a.json"},
        {"name":"r2","url":"https://repo/b.json"},
        {"name":"r3","url":"https://repo/broken.json"}]}"""

    @Test
    fun `multi repo expands with partial failure`() {
        val p = runBlocking {
            SubscriptionParser.parseExpanded(multi) { url ->
                when (url) {
                    "https://repo/a.json" -> repoA
                    "https://repo/b.json" -> repoB
                    else -> error("404")
                }
            }
        }
        assertEquals("multi-repo", p.format)
        assertEquals("多仓", p.name)
        assertEquals(listOf("A1", "B1"), p.vod.map { it.name })
        assertEquals(1, p.skipped)
        assertEquals(3, p.repos)
        assertEquals(1, p.reposFailed)
    }

    @Test
    fun `multi repo dedups across repos`() {
        val m = """{"urls":[{"url":"https://r/1"},{"url":"https://r/2"}]}"""
        val p = runBlocking { SubscriptionParser.parseExpanded(m) { repoA } }
        assertEquals(1, p.vod.size)
    }

    @Test
    fun `multi repo all failing throws`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SubscriptionParser.parseExpanded(multi) { error("down") } }
        }
        assertTrue(e.message!!.contains("多仓展开失败"))
    }

    @Test
    fun `tvbox with urls field still parsed as single config`() {
        val text = """{"sites":[{"name":"A","api":"https://a.com/api.php/provide/vod","type":1}],"urls":[]}"""
        val p = runBlocking { SubscriptionParser.parseExpanded(text) { error("should not fetch") } }
        assertEquals("tvbox", p.format)
        assertEquals(0, p.repos)
    }
}
