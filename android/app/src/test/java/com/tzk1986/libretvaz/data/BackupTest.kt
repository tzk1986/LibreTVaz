package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** TVBox 对标 C13（2026-11-10）：备份 pack/unpack 纯函数——往返一致/类型保真/坏 JSON 不崩/未知类型计数 */
class BackupTest {

    @Test
    fun `round trip preserves all five types`() {
        val snap = mapOf(
            "libretvaz" to mapOf<String, Any?>(
                "s" to "文本", "b" to true, "i" to 42, "l" to 9_000_000_000L, "f" to 1.25f,
            ),
            "libretvaz_sources" to mapOf("subs" to "[\"a\",\"b\"]"),
        )
        val r = Backup.unpack(Backup.pack(snap, ts = 111L))
        assertNotNull(r)
        val (prefs, skipped) = r!!
        assertEquals(0, skipped)
        val p = prefs["libretvaz"]!!
        assertEquals(Backup.Entry('s', "文本"), p["s"])
        assertEquals(Backup.Entry('b', true), p["b"])
        assertEquals(Backup.Entry('i', 42), p["i"])
        assertEquals(Backup.Entry('l', 9_000_000_000L), p["l"])
        assertEquals(Backup.Entry('f', 1.25f), p["f"])
        assertEquals(Backup.Entry('s', "[\"a\",\"b\"]"), prefs["libretvaz_sources"]!!["subs"])
    }

    @Test
    fun `int and long are distinct entry types`() {
        val r = Backup.unpack(Backup.pack(mapOf("x" to mapOf<String, Any?>("a" to 5, "b" to 5L))))!!
        assertEquals('i', r.first["x"]!!["a"]!!.t)
        assertEquals('l', r.first["x"]!!["b"]!!.t)
    }

    @Test
    fun `null values are not packed`() {
        val r = Backup.unpack(Backup.pack(mapOf("x" to mapOf<String, Any?>("a" to null, "b" to "ok"))))!!
        assertNull(r.first["x"]!!["a"])
        assertEquals(Backup.Entry('s', "ok"), r.first["x"]!!["b"])
    }

    @Test
    fun `broken json returns null without crash`() {
        assertNull(Backup.unpack(""))
        assertNull(Backup.unpack("not json at all"))
        assertNull(Backup.unpack("""{"v":1,"ts":1,"app":"LibreTVaz"}""")) // 缺 prefs 段
        assertNull(Backup.unpack("""{"v":99,"ts":1,"prefs":{}}""")) // 版本不符
    }

    @Test
    fun `unknown type tags are skipped and counted`() {
        val json = """{"v":1,"ts":1,"app":"LibreTVaz","prefs":{"lib":{"good":{"t":"s","v":"hi"},"bad":{"t":"x","v":"1"}}}}"""
        val r = Backup.unpack(json)!!
        assertEquals(1, r.second)
        assertNull(r.first["lib"]!!["bad"])
        assertEquals(Backup.Entry('s', "hi"), r.first["lib"]!!["good"])
    }

    @Test
    fun `empty snapshot round trips`() {
        val r = Backup.unpack(Backup.pack(emptyMap()))!!
        assertEquals(emptyMap<String, Map<String, Backup.Entry>>(), r.first)
        assertEquals(0, r.second)
    }
}
