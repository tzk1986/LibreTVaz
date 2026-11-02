package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B11 磁盘层纯函数：键→文件名消毒与命名空间前缀匹配、各编解码 roundtrip、坏数据静默判废 */
class DiskCacheTest {

    @Test
    fun `fileName sanitized deterministic and collision-free across keys`() {
        val a = DiskCache.fileName("aggs:http://x/api|latest")
        val b = DiskCache.fileName("aggs:http://y/api|latest")
        assertTrue(a.startsWith("aggs_"))
        assertEquals(a, DiskCache.fileName("aggs:http://x/api|latest"))
        assertNotEquals(a, b)
        assertTrue(a.all { it.isLetterOrDigit() || it == '_' || it == '.' })
    }

    @Test
    fun `namespace sanitize prefix matches own keys but not neighbors`() {
        assertTrue(DiskCache.fileName("aggs:http://a|电影").startsWith(DiskCache.sanitize("aggs:")))
        assertFalse(DiskCache.fileName("types:http://a").startsWith(DiskCache.sanitize("aggs:")))
        assertTrue(DiskCache.fileName("detail:x|1").startsWith(DiskCache.sanitize("detail:")))
        assertTrue(DiskCache.fileName("zsp:x|1").startsWith(DiskCache.sanitize("zsp:")))
    }

    @Test
    fun `summaries codec roundtrip keeps nulls and flags`() {
        val items = listOf(
            VodSummary("k", "源", "http://a", "1", "庆余年", pic = "http://p", year = "2024", area = "大陆"),
            VodSummary("k", "源", "http://a", "2", "无名", isAdult = true),
        )
        assertEquals(items, CmsCodecs.decSummaries(CmsCodecs.encSummaries(items)))
    }

    @Test
    fun `types codec roundtrip keeps pid`() {
        val ts = listOf(VodType("1", "电影", "0"), VodType("11", "动作", "1"))
        assertEquals(ts, CmsCodecs.decTypes(CmsCodecs.encTypes(ts)))
    }

    @Test
    fun `detail codec roundtrip keeps lines and episodes`() {
        val d = VideoDetail(
            "T", cover = "http://c", desc = null, typeName = "剧情", year = "2025",
            area = "", actor = null, director = null, sourceName = "s",
            lines = listOf(
                PlayLine("极速", listOf(PlayEpisode("第1集", "http://x.m3u8"))),
                PlayLine("备用", emptyList()),
            ),
        )
        assertEquals(d, CmsCodecs.decDetail(CmsCodecs.encDetail(d)))
    }

    @Test
    fun `long codec roundtrip`() {
        assertEquals(12345L, CmsCodecs.decLong(CmsCodecs.encLong(12345L)))
        assertEquals(-1L, CmsCodecs.decLong(CmsCodecs.encLong(-1L)))
    }

    @Test
    fun `garbage payloads decode null not crash`() {
        assertNull(CmsCodecs.decSummaries("not json"))
        assertNull(CmsCodecs.decTypes("{"))
        assertNull(CmsCodecs.decDetail("{\"t\":1}")) // 缺 ln
        assertNull(CmsCodecs.decLong("abc"))
    }

    @Test
    fun `disk ops no-op safely before init`() {
        // 单测进程未 init：读写清都不得抛（内存层独立可用的前提）
        DiskCache.write("aggs:test|k", 1L, "[]")
        assertNull(DiskCache.read("aggs:test|k"))
        assertEquals(0, DiskCache.removePrefix("aggs:"))
        assertEquals(0L, DiskCache.sizeBytes())
        DiskCache.trimTo(0L)
        DiskCache.purgeExpired()
    }

    @Test
    fun `gc ttl table covers all cms namespaces`() {
        val nss = DiskCache.GC_TTL.map { it.first }
        listOf("types:", "aggs:", "detail:", "zsp:", "search:").forEach { assertTrue(nss.contains(it)) }
    }
}
