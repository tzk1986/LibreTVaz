package com.tzk1986.libretvaz.data.perf

import com.tzk1986.libretvaz.data.PlayEpisode
import com.tzk1986.libretvaz.data.PlayLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** perf-core.js web 测试套的 Kotlin 对齐断言（B0） */
class PerfCoreTest {

    @Test fun `norm 去尾斜杠与空白`() {
        assertEquals("http://a.com", PerfCore.norm(" http://a.com// "))
        assertEquals("", PerfCore.norm(null))
    }

    private data class Src(val si: Int, val ms: Long?)

    @Test fun `orderSources ms升序 未测按源序 当前源最优先`() {
        val items = listOf(Src(0, 100), Src(1, 50), Src(2, null), Src(3, 80))
        assertEquals(listOf(1, 3, 0, 2), PerfCore.orderSources(items, 1, { it.si }, { it.ms }).map { it.si })
        assertEquals(listOf(0, 1, 2, 3), PerfCore.orderSources(items, -1, { it.si }, { null }).map { it.si })
        assertEquals(listOf(2, 1, 3, 0), PerfCore.orderSources(items, 2, { it.si }, { it.ms }).map { it.si })
    }

    @Test fun `splitBatches top8 首批`() {
        val (a, b) = PerfCore.splitBatches((1..10).toList())
        assertEquals(8 to 2, a.size to b.size)
        val (c, d) = PerfCore.splitBatches((1..3).toList())
        assertEquals(3 to 0, c.size to d.size)
    }

    @Test fun `searchKey trim加小写`() {
        assertEquals("search:ab", PerfCore.searchKey("  Ab "))
    }

    @Test fun `splitTtl fresh stale`() {
        val now = 1_000_000L
        val shards = mapOf(
            "http://a" to PerfCore.Shard(now - 60_000, listOf("x")),
            "http://b" to PerfCore.Shard(now - 11 * 60_000, listOf("y")),
        )
        val r = PerfCore.splitTtl(shards, PerfCore.SEARCH_TTL, now)
        assertEquals(listOf("http://a"), r.fresh.keys.toList())
        assertEquals(listOf("http://b"), r.stale.keys.toList())
        assertTrue(PerfCore.splitTtl<String>(null, 1000, now).fresh.isEmpty())
    }

    @Test fun `epoch bump 后旧token失效`() {
        val e = PerfCore.Epoch()
        val t = e.bump()
        assertFalse(e.stale(t))
        e.bump()
        assertTrue(e.stale(t))
    }

    @Test fun `coreName 装饰剥离`() {
        assertEquals("复仇者联盟", PerfCore.coreName("复仇者联盟4：终局之战"))
        assertEquals("庆余年", PerfCore.coreName("庆余年 第二季"))
        assertEquals("毒液", PerfCore.coreName("毒液2"))
        assertEquals("甄嬛传", PerfCore.coreName("甄嬛传"))
        assertEquals("某某", PerfCore.coreName("某某 更新至12集"))
        assertEquals("", PerfCore.coreName("大"))
        assertEquals("", PerfCore.coreName(""))
    }

    @Test fun `truncateByQuery 聚合与噪音防护`() {
        val hits = listOf("复仇者联盟", "复仇者联盟2")
        assertEquals(listOf("复仇者"), PerfCore.truncateByQuery("fcz", hits))
        assertTrue(PerfCore.truncateByQuery("fcz", listOf("复仇者联盟")).isEmpty()) // 单命中不出
        assertTrue(PerfCore.truncateByQuery("f", hits).isEmpty()) // 长度<2
        assertTrue(PerfCore.truncateByQuery("fcz1", hits).isEmpty()) // 非纯字母
    }

    @Test fun `truncateByQuery 多音字变体与匹配位对齐`() {
        val idx = PinIndex()
        idx.addMany(listOf("复仇者联盟", "英雄联盟"))
        assertTrue(idx.ok)
        val hits = listOf("复仇者联盟", "英雄联盟")
        // lm 在两词首字母串（fczlm / yxlm）的匹配位都指向「联盟」，不再从词首错截
        assertEquals(listOf("联盟"), PerfCore.truncateByQuery("lm", hits, variantsOf = { idx.variants(it) }))
    }

    @Test fun `relCandidates 搜关联候选词打散`() {
        assertEquals(listOf("NCIS", "NCIS New York", "NCIS 第一季", "NCIS: New York 第一季"), PerfCore.relCandidates("NCIS: New York 第一季"))
        assertEquals(listOf("庆余年", "庆余年 第二季"), PerfCore.relCandidates("庆余年 第二季"))
        assertEquals(listOf("甄嬛传"), PerfCore.relCandidates("甄嬛传"))
        assertEquals(listOf("毒液", "毒液2"), PerfCore.relCandidates("毒液2"))
        assertEquals(listOf("复仇者联盟", "复仇者联盟4", "复仇者联盟4：终局之战"), PerfCore.relCandidates("复仇者联盟4：终局之战"))
        assertEquals(emptyList<String>(), PerfCore.relCandidates(null))
        assertTrue(PerfCore.relCandidates("测试 一二 三四 五六 七八 九十 十一").size <= 6)
    }

    @Test fun `rewriteM3u8 分片与KEY MAP包代理`() {
        val base = "http://host/v/p/index.m3u8"
        val enc = { u: String -> "/proxy?url=" + PerfCore.encodeURIComponent(u) }
        val src = listOf(
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/proxy?url=http%3A%2F%2Fx.com%2Fkey2.bin\"",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:10,",
            "seg.ts",
            "/abs.ts",
            "https://cdn.x.com/a.ts",
        ).joinToString("\n")
        val out = PerfCore.rewriteM3u8(src, base)
        val lines = out.split("\n")
        assertEquals("#EXTM3U", lines[0])
        assertTrue(lines[1].endsWith("URI=\"" + enc("http://host/v/p/key.bin") + "\""))
        assertEquals(lines[2], src.split("\n")[2]) // 已代理不二次改写
        assertTrue(lines[3].endsWith("URI=\"" + enc("http://host/v/p/init.mp4") + "\""))
        assertEquals("#EXTINF:10,", lines[4])
        assertEquals(enc("http://host/v/p/seg.ts"), lines[5])
        assertEquals(enc("http://host/abs.ts"), lines[6])
        assertEquals(enc("https://cdn.x.com/a.ts"), lines[7])
    }

    @Test fun `rewriteM3u8 CRLF 保留`() {
        val out = PerfCore.rewriteM3u8("seg.ts\r\n#EXT-X-ENDLIST", "http://host/a/index.m3u8")
        assertTrue(out.startsWith("/proxy?url="))
        assertTrue(out.split("\n")[0].endsWith("\r"))
    }

    @Test fun `encodeURIComponent 等价`() {
        assertEquals("http%3A%2F%2Fhost%2Fv%2Fseg.ts", PerfCore.encodeURIComponent("http://host/v/seg.ts"))
        assertEquals("a-b_c.d~e!'()*", PerfCore.encodeURIComponent("a-b_c.d~e!'()*")) // !'()* 为不转义字符
        assertEquals("%E4%B8%AD", PerfCore.encodeURIComponent("中"))
    }

    @Test fun `normName 频道名归一`() {
        assertEquals("CCTV1", PerfCore.normName("CCTV-1 高清"))
        assertEquals("CCTV1", PerfCore.normName("cctv1"))
        assertEquals("CCTV1", PerfCore.normName("CCTV 1"))
        assertEquals("湖南卫视", PerfCore.normName("湖南卫视"))
    }

    @Test fun `tsNum 取14位数字`() {
        assertEquals(20261102093000L, PerfCore.tsNum("20261102093000 +0800"))
        assertEquals(0L, PerfCore.tsNum("2026"))
        assertEquals(0L, PerfCore.tsNum(null))
    }

    @Test fun `epg 解析与按天匹配`() {
        val xml = "<tv><channel id=\"cctv1\"><display-name>CCTV-1 高清</display-name></channel>" +
            "<programme channel=\"cctv1\" start=\"20261102080000\" stop=\"20261102090000\"><title>朝闻天下</title></programme>" +
            "<programme channel=\"cctv1\" start=\"20261102090000\"><title>晚间新闻 &amp; 点播</title></programme>" +
            "<programme channel=\"cctv1\" start=\"20261103100000\"><title>明日秀</title></programme>" +
            "</tv>"
        val idx = PerfCore.parseXmltv(xml)
        assertEquals("cctv1", idx.byName["CCTV1"])
        val list = PerfCore.epgFor(idx, "CCTV1", 20261102083000L)
        assertEquals(listOf("朝闻天下", "晚间新闻 & 点播"), list.map { it.title })
        assertEquals("朝闻天下", PerfCore.epgNow(list, 20261102083000L)?.title)
        assertEquals("晚间新闻 & 点播", PerfCore.epgNow(list, 20261102093000L)?.title) // 无 stop 视为进行中
        assertNull(PerfCore.epgNow(list, 20261102070000L))
        assertEquals(emptyList<String>(), PerfCore.epgFor(idx, "湖南卫视", 20261102000000L))
    }

    @Test fun `speedTag 三档与失败语义`() {
        assertNull(PerfCore.lineSpeedTag(null))
        assertEquals(PerfCore.SpeedTag("测速失败", "sp-dim"), PerfCore.lineSpeedTag(-1))
        assertEquals(PerfCore.SpeedTag("慢/不可用", "sp-bad"), PerfCore.lineSpeedTag(0))
        assertEquals(PerfCore.SpeedTag("149KB/s 慢/不可用", "sp-bad"), PerfCore.lineSpeedTag(149))
        assertEquals(PerfCore.SpeedTag("150KB/s 可能卡顿", "sp-mid"), PerfCore.lineSpeedTag(150))
        assertEquals(PerfCore.SpeedTag("702KB/s 可能卡顿", "sp-mid"), PerfCore.lineSpeedTag(702))
        assertEquals(PerfCore.SpeedTag("1.5MB/s 可能卡顿", "sp-mid"), PerfCore.lineSpeedTag(1499))
        assertEquals(PerfCore.SpeedTag("1.5MB/s 流畅", "sp-ok"), PerfCore.lineSpeedTag(1500))
        assertEquals(PerfCore.SpeedTag("8.8MB/s 流畅", "sp-ok"), PerfCore.lineSpeedTag(9000))
    }

    private fun line(vararg links: String) =
        PlayLine("l", links.mapIndexed { i, u -> PlayEpisode("e$i", u) })

    @Test fun `pickNextLine 不可用沉底 流后缀优先 同级保线序`() {
        val lines = listOf(
            line("http://a/1.m3u8"),
            line("http://b/share"),
            line("http://c/1.m3u8"),
            line("http://d/share"),
            line("http://e/1.m3u8"),
        )
        val speeds = listOf(900L, 0L, 3000L, 100L, null)
        val got = PerfCore.pickNextLine(lines, line = 0, speeds = speeds).map { it.index }
        assertEquals(listOf(2, 4, 1, 3), got)
        // 无 speeds：流后缀优先，其余保线序
        assertEquals(listOf(2, 4, 1, 3), PerfCore.pickNextLine(lines, line = 0).map { it.index })
        // -1 采样失败按未知不沉底
        val s2 = listOf(900L, -1L, 3000L, -1L, null)
        assertEquals(listOf(2, 4, 1, 3), PerfCore.pickNextLine(lines, line = 0, speeds = s2).map { it.index })
        // 排除当前/已试；全试过收敛
        assertEquals(listOf(2, 4, 1, 3), PerfCore.pickNextLine(lines, line = 0, tried = setOf(0)).map { it.index })
        assertTrue(PerfCore.pickNextLine(lines, line = 0, tried = setOf(1, 2, 3, 4)).isEmpty())
    }

    @Test fun `pickNextLine 集数不齐回落第1集`() {
        val lines = listOf(line("http://a/1.m3u8"), line("http://b/e1.m3u8"))
        val got = PerfCore.pickNextLine(lines, line = 0, curEp = 5)
        assertEquals(1, got.size)
        assertEquals("http://b/e1.m3u8", got[0].episode.link)
    }

    @Test fun `pickBestLine 已测最快流后缀线 无测回退`() {
        val lines = listOf(
            line("http://a/share1"),
            line("http://b/1.m3u8"),
            line("http://c/1.mp4"),
            line("http://d/share2"),
        )
        assertEquals(2, PerfCore.pickBestLine(lines, listOf(500L, 900L, 3000L, 200L))) // c 最快（d 非流后缀排除）
        assertEquals(1, PerfCore.pickBestLine(lines, listOf(100L, null, null, null))) // 无有效测速→首个流后缀线
        assertEquals(0, PerfCore.pickBestLine(listOf(line("http://x/share")), listOf(5000L))) // 全壳页→首个非空
    }

    @Test fun `classify 地区判定`() {
        assertEquals("DOM", PerfCore.classifyVod(null, "港剧"))
        assertEquals("EUUS", PerfCore.classifyVod("美剧", "大陆"))
        assertEquals("EUUS", PerfCore.classifyVod(null, "美国"))
        assertEquals("KR", PerfCore.classifyVod(null, null, "韩国综艺"))
        assertEquals("UNKNOWN", PerfCore.classifyVod(null, null, null))
        assertEquals("EUUS", PerfCore.classifyVod(null, "美"))
        assertEquals("OTHER", PerfCore.regionOfArea("其它"))
        assertEquals("JP", PerfCore.regionOfArea("日本，美国"))
        assertNull(PerfCore.regionOfArea("未知领域"))
        assertEquals(listOf("JP", "KR"), PerfCore.regionLabels("日韩综艺"))
        assertEquals(emptyList<String>(), PerfCore.regionLabels("爱情"))
    }

    @Test fun `VOD_FILTERS 预设语义`() {
        assertEquals(listOf("EUUS", "JP", "KR", "OTHER"), PerfCore.VOD_FILTERS["电影"]!!.first { it.first == "国外" }.second)
        assertEquals(listOf("OTHER"), PerfCore.VOD_FILTERS["电视剧"]!!.first { it.first == "小语种" }.second)
        assertTrue(PerfCore.VOD_FILTERS["电影"]!!.first { it.first == "全部" }.second.isEmpty())
        assertNotNull(PerfCore.VOD_FILTERS["动漫"])
    }

    @Test fun `MemCache TTL 与 LRU 逐出`() {
        val c = MemCache<String>(maxEntries = 2)
        c.set("a", "1", now = 0)
        c.set("b", "2", now = 0)
        c.get("a", ttl = Long.MAX_VALUE, now = 1) // a 变最近使用
        c.set("c", "3", now = 2) // 超上限逐出 b
        assertNull(c.getRaw("b"))
        assertNotNull(c.getRaw("a"))
        assertEquals("1", c.get("a", ttl = Long.MAX_VALUE, now = 2))
        assertNull(c.get("a", ttl = 1, now = 5)) // TTL 过期
        c.set("d", "4", now = 5)
        c.set("search:x", "s", now = 5)
        assertEquals(1, c.removePrefix("search:"))
        assertNull(c.getRaw("search:x"))
    }

    @Test fun `KeyDedupe 同key并发合并`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val d = KeyDedupe<Int>()
        val job1 = async { d.run("k") { calls.incrementAndGet(); gate.await(); 42 } }
        delay(30)
        val job2 = async { d.run("k") { calls.incrementAndGet(); gate.await(); 99 } }
        delay(30)
        gate.complete(Unit)
        assertEquals(42, job1.await())
        assertEquals(42, job2.await())
        assertEquals(1, calls.get())
        // 完成后清槽：再次执行
        assertEquals(7, d.run("k") { 7 })
    }
}
