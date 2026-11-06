package com.tzk1986.libretvaz.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `shard key normalizes url so trailing slash variants share one shard`() {
        // 走查 #16：站内「http://a/」与「http://a」双写法原各占一份分片（互不复用、双份拉取）
        assertEquals(Agg.key(src("A", "http://a"), null), Agg.key(src("A", "http://a/"), null))
    }

    @Test
    fun `poolOf takes twelve by health ascending`() {
        // B15 扩池定稿：POOL_LIMIT=12（首波 top6+二波 7~12），慢源 13 出局
        val sources = (1..13).map { src("s$it", "http://s$it") }
        val pool = Agg.poolOf(sources) { s -> if (s.url == "http://s13") 999L else s.name.substring(1).toLong() }
        assertEquals(12, pool.size)
        assertEquals("s1", pool.first().name)
        assertFalse(pool.any { it.name == "s13" })
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
        VodType("24", "台剧", "2"),
        VodType("25", "港剧", "2"),
        VodType("26", "泰剧", "2"),
        VodType("27", "海外剧", "2"),
        VodType("3", "剧情片", "0"),
    )

    @Test
    fun `subCatsFor regular cat takes six kids, region cat filters by rlabs`() {
        // 方案B（用户 2026-11-06 批准）：常规子类 3→6 扩面——实测多数源日剧排第 6，take(3) 永不含区子类
        val tv = Agg.AGG_CATS[1]
        assertEquals(listOf("21", "22", "23", "24", "25", "26"), Agg.subCatsFor(tree, tv).map { it.id })
        val jp = tv.copy(rlabs = listOf("JP")) // 日韩剧含「日韩」→ JP 码命中（引擎保留定向能力；聚合墙已改纯客户端过滤不再改道）
        assertEquals(listOf("22"), Agg.subCatsFor(tree, jp).map { it.id })
        // 地区无命中子类 → 回落父类混拉（靠条目 Region.of 客户端过滤）
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
    fun `regionCatOf no-ops without labs else carries them`() {
        // 方案A：chip 选择态构造定向 cat；「全部」/单源墙 labs 空=原样返回不改道
        val tv = Agg.AGG_CATS[1]
        assertEquals(tv, Agg.regionCatOf(tv, emptyList()))
        assertNull(Agg.regionCatOf(null, emptyList()))
        val rc = Agg.regionCatOf(tv, listOf("JP"))
        assertEquals(listOf("JP"), rc?.rlabs)
        assertEquals("电视剧", rc?.key)
        assertEquals(tv.kw, rc?.kw)
    }

    @Test
    fun `preferRegionFirst puts sources with region kid ahead, keeps rest order`() {
        val pool = listOf(src("a", "http://a"), src("b", "http://b"), src("c", "http://c"))
        val out = Agg.preferRegionFirst(pool, setOf("http://c"))
        assertEquals(listOf("c", "a", "b"), out.map { it.name })
    }

    @Test
    fun `topMatches takes four case-insensitive top-level types by kw`() {
        // 资源口定向增补选择器：顶层类名含关键词（忽略大小写），前 4 封顶，子类不参与
        val z = listOf(
            VodType("9", "日本无码", "0"),
            VodType("10", "VR无码", "0"),
            VodType("11", "jk制服", "0"),
            VodType("12", "国漫", "0"),
            VodType("13", "制服二级", "9"), // 非顶层不计
        )
        assertEquals(listOf("9", "10", "11"), Agg.topMatches(z, listOf("无码", "JK", "制服")).map { it.id })
        assertEquals(emptyList<String>(), Agg.topMatches(z, listOf("不存在的词")).map { it.id })
        // 前 4 封顶
        val many = (1..6).map { VodType("$it", "无码$it", "0") }
        assertEquals(4, Agg.topMatches(many, listOf("无码")).size)
    }

    @Test
    fun `zoneTagKey keeps source and tag in shard key`() {
        val s = src("S", "http://x/api")
        assertEquals("aggs:http://x/api|zone:有码", Agg.zoneTagKey(s, "有码"))
    }

    @Test
    fun `hasRegionKid only true when kid matches rlabs`() {
        assertTrue(Agg.hasRegionKid(tree, Agg.AGG_CATS[1].copy(rlabs = listOf("JP"))))
        assertTrue(Agg.hasRegionKid(tree, Agg.AGG_CATS[1].copy(rlabs = listOf("EUUS"))))
        assertFalse(Agg.hasRegionKid(tree, Agg.AGG_CATS[0].copy(rlabs = listOf("OTHER")))) // 电影树无地区子类（电视剧子树的泰剧算 OTHER，勿用）
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

    @Test
    fun `yearAsc sorts oldest first with no-year sinking to tail`() {
        val items = listOf(item("c", "c", year = "2023"), item("a", "a", year = "2025"), item("b", "b", year = "2025"), item("d", "d", year = null))
        assertEquals(listOf("c", "a", "b", "d"), Agg.yearAsc(items).map { it.vodId })
    }

    // 终审 #1（Critical）：单源墙 tp1 直查无 dedup → LazyGrid key 重复即闪退
    @Test
    fun `firstWallOf drops same-id repeats so grid keys stay unique`() {
        val page = listOf(item("甲", "1"), item("甲·重复回", "1"), item("乙", "2"))
        val out = Agg.firstWallOf(page)
        assertEquals(listOf("1", "2"), out.map { it.vodId })
        assertEquals("甲", out.first().name) // 保留首见，不被重复项顶替
    }

    @Test
    fun `firstWallOf keeps year-descending head`() {
        val out = Agg.firstWallOf(listOf(item("旧", "1", year = "2020"), item("新", "2", year = "2026")))
        assertEquals(listOf("新", "旧"), out.map { it.name })
    }

    @Test
    fun `aggMoreContinue caps client filtered walls at ten rounds`() {
        // 日剧类窄过滤下整轮多为非命中条目，AutoLoadMore 底栏恒可见自链=「一直加载中」；R8 同构封顶
        assertTrue(Agg.aggMoreContinue(9, true, true))
        assertFalse(Agg.aggMoreContinue(10, true, true))
        assertTrue(Agg.aggMoreContinue(10, false, true)) // 无过滤维持 800 封顶/FUSE 原判底
        assertTrue(Agg.aggMoreContinue(Agg.FUSE_ROUND - 1, false, true))
        assertFalse(Agg.aggMoreContinue(Agg.FUSE_ROUND, true, true))
        assertFalse(Agg.aggMoreContinue(1, true, false)) // 全池封顶=到底（与过滤无关）
        assertFalse(Agg.aggMoreContinue(1, false, false))
    }
}
