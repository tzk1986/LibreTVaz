package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.PerfCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 用户 2026-11-06 批准（走查 #2/#3）：搜索分片外层 LRU 封顶 + persistSoon 防抖合并落盘 */
class SearchShardsTest {

    private val writes = mutableListOf<Triple<String, Long, String>>()
    private val reads = mutableListOf<String>()
    private val disk = HashMap<String, Pair<Long, String>>()

    @Before
    fun setUp() {
        writes.clear(); reads.clear(); disk.clear()
        SearchShards.resetForTests(
            sink = { k, t, p -> writes.add(Triple(k, t, p)); disk[k] = t to p },
            source = { k -> reads.add(k); disk[k] },
        )
        SearchShards.clearMem()
    }

    private fun shardOf(vararg names: String) =
        PerfCore.Shard(1L, names.map { VodSummary("http://a", "源", "http://a", it, it) })

    @Test
    fun `persistSoon merges multiple calls into one flush write with latest content`() {
        SearchShards.persistSoon("search:x", mapOf("u1" to shardOf("a")))
        SearchShards.persistSoon("search:x", mapOf("u1" to shardOf("a"), "u2" to shardOf("b")))
        SearchShards.flushAll()
        assertEquals(1, writes.count { it.first == "search:x" })
        val payload = writes.last().third
        assertTrue(payload.contains("a") && payload.contains("b"))
    }

    @Test
    fun `flushAll writes every pending key`() {
        SearchShards.persistSoon("search:x", mapOf("u1" to shardOf("a")))
        SearchShards.persistSoon("search:y", mapOf("u1" to shardOf("b")))
        SearchShards.flushAll()
        assertEquals(setOf("search:x", "search:y"), writes.map { it.first }.toSet())
    }

    @Test
    fun `shardsOf backfills from disk when memory empty`() {
        SearchShards.persistSoon("search:x", mapOf("u1" to shardOf("a")))
        SearchShards.flushAll()
        SearchShards.clearMem()
        val m = SearchShards.shardsOf("search:x")
        assertEquals(listOf("u1"), m.keys.toList())
        assertEquals(listOf("a"), m.getValue("u1").d.map { it.name })
    }

    @Test
    fun `outer cache evicts oldest beyond thirty keys`() {
        // 31 个 ck 各从盘回灌非空分片；首个（最久未访问）应被逐出→再次 shardsOf 触发盘读
        repeat(31) { i ->
            val ck = "search:k%02d".format(i)
            disk[ck] = 1L to """{"u1":{"t":1,"v":${CmsCodecs.encSummaries(listOf(VodSummary("http://a", "源", "http://a", "id$i", "n$i")))}}}"""
            SearchShards.shardsOf(ck)
        }
        reads.clear()
        SearchShards.shardsOf("search:k00") // 已逐出 → 读盘
        SearchShards.shardsOf("search:k30") // 最近访问 → 命中内存不读盘
        assertEquals(listOf("search:k00"), reads)
    }

    @Test
    fun `clearAll cancels scheduled persist so purged shards do not revive`() {
        // 终审 #3：搜索结束 500ms 内点「清理缓存」，在排防抖不得把已删分片回写复活
        SearchShards.persistSoon("search:z", mapOf("u1" to shardOf("a")))
        SearchShards.clearAll()
        Thread.sleep(750) // 500ms 防抖任务若未被取消此时应已执行
        assertTrue("purged key rewritten after clearAll", writes.none { it.first == "search:z" })
    }

    @Test
    fun `live map snapshot decoupled from later mutation`() {
        val live = java.util.concurrent.ConcurrentHashMap<String, PerfCore.Shard<VodSummary>>()
        live["u1"] = shardOf("a")
        SearchShards.persistSoon("search:x", live)
        live["u2"] = shardOf("c") // persistSoon 之后改内存表，不应影响已排队快照
        SearchShards.flushAll()
        assertTrue(!writes.last().third.contains("\"c\""))
    }
}
