package com.tzk1986.libretvaz.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B10 聚合墙引擎纯函数：分片键、子类选择、地区收录判定、队列化判底所需的合并/追加去重 */
class AggTest {

    @After
    fun tearDown() = Agg.clearAll()

    private fun src(name: String, url: String) = VodSource(name, url)

    private fun item(name: String, id: String = name, url: String = "http://a", year: String? = null) =
        VodSummary(url, "源", url, id, name, year = year)

    @Test
    fun `shard key matches web aggKey format`() {
        val s = src("S", "http://x/api")
        assertEquals("aggs:http://x/api|latest", Agg.key(s, null))
        assertEquals("aggs:http://x/api|电影", Agg.key(s, Agg.AGG_CATS[0]))
        val rl = Agg.AGG_CATS[1].copy(rlabs = listOf("日本", "韩国"))
        assertEquals("aggs:http://x/api|电视剧@日本,韩国", Agg.key(s, rl))
    }

    @Test
    fun `poolOf takes six by health ascending`() {
        val sources = (1..8).map { src("s$it", "http://s$it") }
        val pool = Agg.poolOf(sources) { s -> if (s.url == "http://s7") 999L else s.name.last().digitToInt().toLong() }
        assertEquals(6, pool.size)
        assertEquals("s1", pool.first().name)
        assertFalse(pool.any { it.name == "s7" }) // 7 排在 8 后？升序 1..6 取前 6，7/8 出局
        assertFalse(pool.any { it.name == "s8" })
    }

    private val tree = listOf(
        VodType("1", "电影", "0"),
        VodType("11", "动作", "1"),
        VodType("12", "喜剧", "1"),
        VodType("13", "科幻", "1"),
        VodType("14", "恐怖", "1"),
        VodType("2", "电视剧", ""),
        VodType("21", "国产剧", "2"),
        VodType("22", "日韩剧", "2"),
        VodType("23", "美剧", "2"),
        VodType("3", "剧情片", "0"),
    )

    @Test
    fun `subCatsFor regular cat takes three kids, region cat filters by rlabs`() {
        val tv = Agg.AGG_CATS[1]
        assertEquals(listOf("21", "22", "23"), Agg.subCatsFor(tree, tv).map { it.id })
        val jp = tv.copy(rlabs = listOf("JP")) // 日韩剧含「日韩」→ JP 码命中
        assertEquals(listOf("22"), Agg.subCatsFor(tree, jp).map { it.id })
        // 地区无命中子类 → 回落父类混拉（靠条目 classifyVod 客户端过滤）
        val doc = Agg.AGG_CATS[0].copy(rlabs = listOf("JP"))
        assertEquals(listOf("1"), Agg.subCatsFor(tree, doc).map { it.id })
        // 源分类树无该父类 → 空贡献
        assertTrue(Agg.subCatsFor(tree, Agg.AGG_CATS[2]).isEmpty())
    }

    @Test
    fun `subCatsFor uses parent when no kids`() {
        val flat = listOf(VodType("5", "综艺", "0"))
        assertEquals(listOf("5"), Agg.subCatsFor(flat, Agg.AGG_CATS[2]).map { it.id })
    }

    @Test
    fun `hasRegionKid only true when kid matches rlabs`() {
        assertTrue(Agg.hasRegionKid(tree, Agg.AGG_CATS[1].copy(rlabs = listOf("JP"))))
        assertTrue(Agg.hasRegionKid(tree, Agg.AGG_CATS[1].copy(rlabs = listOf("EUUS"))))
        assertFalse(Agg.hasRegionKid(tree, Agg.AGG_CATS[1].copy(rlabs = listOf("OTHER"))))
        assertFalse(Agg.hasRegionKid(tree, Agg.AGG_CATS[1])) // 无 rlabs 恒 false
    }

    @Test
    fun `mergeShards dedups by normalized name across pool`() {
        val a = src("A", "http://a"); val b = src("B", "http://b")
        Agg.shards.set(Agg.key(a, null), listOf(item("庆余年", id = "1", url = "http://a"), item("A片", url = "http://a")))
        Agg.shards.set(Agg.key(b, null), listOf(item("庆余年", id = "2", url = "http://b"), item("庆余年 第二季", id = "3", url = "http://b")))
        val merged = Agg.mergeShards(listOf(a, b), null)
        // 同名跨源并掉；「庆余年 第二季」归一后是另一片名保留；A片保留
        assertEquals(listOf("庆余年", "A片", "庆余年 第二季"), merged.map { it.name })
    }

    @Test
    fun `putShard dedups by id then caps depth`() {
        val a = src("A", "http://a")
        val first = (1 until Agg.DEPTH).map { item("片$it", id = "$it", url = "http://a") }
        Agg.putShard(a, null, first + listOf(item("重复", id = "dup", url = "http://a"), item("重复", id = "dup", url = "http://a")))
        val stored = Agg.shards.getRaw(Agg.key(a, null))!!.v
        assertEquals(Agg.DEPTH, stored.size) // 799+1=800 恰好封顶
        assertEquals(1, stored.count { it.name == "重复" }) // dedupById 保留首个
    }

    @Test
    fun `appendShard dedups by id and at-cap drops new tail (natural bottom)`() {
        val a = src("A", "http://a")
        val first = (1 until Agg.DEPTH).map { item("片$it", id = "$it", url = "http://a") }
        Agg.putShard(a, null, first)
        assertEquals(Agg.DEPTH - 1, Agg.shards.getRaw(Agg.key(a, null))!!.v.size)
        Agg.appendShard(a, null, listOf(item("片1", id = "1", url = "http://a"), item("新片", id = "n", url = "http://a")))
        val after = Agg.shards.getRaw(Agg.key(a, null))!!.v
        assertEquals(Agg.DEPTH, after.size)
        assertEquals(1, after.count { it.vodId == "1" }) // 重复页 dedupById 吃掉
        assertTrue(after.any { it.name == "新片" })

        // 封顶后再追加：新条目被 take 挤出 → 整墙无新增 → 上层据此判到底（web 同构行为）
        Agg.appendShard(a, null, listOf(item("另一新片", id = "m", url = "http://a")))
        assertFalse(Agg.shards.getRaw(Agg.key(a, null))!!.v.any { it.name == "另一新片" })
    }

    @Test
    fun `fresh false when older than ttl and true after write`() {
        val a = src("A", "http://a")
        assertFalse(Agg.fresh(a, null, 1000))
        Agg.shards.set(Agg.key(a, null), listOf(item("x", url = "http://a")))
        assertTrue(Agg.fresh(a, null, 60_000))
        assertFalse(Agg.fresh(a, null, 60_000, now = System.currentTimeMillis() + 61_000))
    }

    @Test
    fun `yearDesc sorts by leading year keeping stable order within same year`() {
        val items = listOf(item("c", "c", year = "2023"), item("a", "a", year = "2025"), item("b", "b", year = "2025"), item("d", "d", year = null))
        assertEquals(listOf("a", "b", "c", "d"), Agg.yearDesc(items).map { it.vodId })
    }
}
