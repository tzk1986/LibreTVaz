package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.KeyDedupe
import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 聚合墙分类（web AGG_CATS 元素）：kw 匹配各源顶层分类名；rlabs=地区预设目标标签（空=不按地区定向） */
data class AggCat(val key: String, val kw: List<String>, val rlabs: List<String> = emptyList())

/**
 * 聚合墙引擎（web aggPool/fetchAggSource/runAggWall/aggLoadMore/aggPrefetch 等价）：
 * 分片按源存双层缓存 DiskBacked（内存 LRU+磁盘回灌，键 aggs:源|分类[@地区]），首绘用已有分片秒开，续轮各源 pg+1
 * 追加进分片（dedupById、每源 800 部封顶）再整墙重并——队列化只追加未见片名，不动已上屏条目。
 * 判底以「本轮整墙是否有新增」为准（无轮次上限；300 轮仅极端保险丝），单源失败静默。
 * 地区预设：分类树收录该区的源排前；墙拉起后低并发预取其余分类（只进分片不渲染，切类秒开）。
 */
object Agg {
    const val POOL_SIZE = 6            // 池上限：全并行即首墙只等最快那批源
    const val DEPTH = 800              // 每源分片深度封顶（防内存膨胀，到界自然无新增=到底）
    const val FUSE_ROUND = 300         // 保险丝轮次：正常以增量判底，到不了这里
    private const val TYPES_TTL = 6 * 3600_000L

    val AGG_CATS = listOf(
        AggCat("电影", listOf("电影", "影片")),
        AggCat("电视剧", listOf("电视剧", "连续剧", "剧集", "网剧")),
        AggCat("综艺", listOf("综艺", "节目")),
        AggCat("动漫", listOf("动漫", "动画", "番")),
    )

    val shards = DiskBacked(MemCache<List<VodSummary>>(256), "aggs:", CmsCodecs::encSummaries, CmsCodecs::decSummaries)
    private val typesC = DiskBacked(MemCache<List<VodType>>(64), "types:", CmsCodecs::encTypes, CmsCodecs::decTypes)
    private val typesDedupe = KeyDedupe<List<VodType>>()

    fun key(s: VodSource, cat: AggCat?): String =
        "aggs:" + s.url + "|" + if (cat == null) "latest"
        else cat.key + if (cat.rlabs.isNotEmpty()) "@" + cat.rlabs.joinToString(",") else ""

    /** 健康 ms 排前 6：死源不排除自然沉底，未测按中性 4000ms */
    fun poolOf(sources: List<VodSource>, msOf: (VodSource) -> Long): List<VodSource> =
        sources.sortedBy { msOf(it) }.take(POOL_SIZE)

    fun healthMs(health: HealthStore, s: VodSource): Long {
        val h = health.of(s.url) ?: return 4000L
        return if (h.fails >= HealthStore.DEAD_FAILS) Long.MAX_VALUE else h.ms ?: 4000L
    }

    fun fresh(s: VodSource, cat: AggCat?, ttl: Long, now: Long = System.currentTimeMillis()): Boolean =
        shards.getRaw(key(s, cat))?.let { it.v.isNotEmpty() && now - it.t < ttl } == true

    /** 分类树：6h 新鲜缓存（B11 起双层：内存+磁盘回灌）+ 同源并发合并；拉新失败回退旧条目（web getSrcTypes 等价） */
    suspend fun typesOf(repo: CmsRepository, s: VodSource): List<VodType> {
        val key = "types:" + s.url
        typesC.get(key, TYPES_TTL)?.let { return it }
        return runCatching {
            typesDedupe.run(s.url) {
                val fresh = repo.types(s)
                if (fresh.isNotEmpty()) { typesC.set(key, fresh); fresh }
                else typesC.getRaw(key)?.v ?: emptyList()
            }
        }.getOrElse { typesC.getRaw(key)?.v ?: emptyList() }
    }

    private fun isTop(t: VodType) = t.pid.isEmpty() || t.pid == "0"

    private fun topOf(types: List<VodType>, cat: AggCat): VodType? =
        types.firstOrNull { isTop(it) && cat.kw.any { k -> it.name.contains(k) } }

    /** 源分类树里是否存在命中 cat.rlabs 的地区子类（=该源收录该区），聚合墙据此排优先级 */
    fun hasRegionKid(types: List<VodType>, cat: AggCat): Boolean {
        if (cat.rlabs.isEmpty()) return false
        val p = topOf(types, cat) ?: return false
        return types.any { it.pid == p.id && it.id != p.id && PerfCore.regionLabels(it.name).any { l -> cat.rlabs.contains(l) } }
    }

    /** 该源在 cat 下要拉的子集：地区预设取命中子类前 4（无则回落父类混拉）；常规取前 3 子类（无子类用父类） */
    fun subCatsFor(types: List<VodType>, cat: AggCat): List<VodType> {
        val p = topOf(types, cat) ?: return emptyList()
        val kids = types.filter { it.pid == p.id && it.id != p.id }
        return if (cat.rlabs.isNotEmpty()) {
            val reg = kids.filter { c -> PerfCore.regionLabels(c.name).any { l -> cat.rlabs.contains(l) } }
            if (reg.isNotEmpty()) reg.take(4) else listOf(p)
        } else if (kids.isNotEmpty()) kids.take(3) else listOf(p)
    }

    /** 单源对墙的贡献：cat=null 取全量最新页，否则并行拉子分类列表页（repo 内部已 8s 快断吞错） */
    suspend fun fetchSource(repo: CmsRepository, s: VodSource, cat: AggCat?, pg: Int): List<VodSummary> {
        if (cat == null) return repo.home(s, pg)
        val qs = subCatsFor(typesOf(repo, s), cat)
        if (qs.isEmpty()) return emptyList()
        return coroutineScope { qs.map { c -> async { repo.listByType(s, c.id, pg) } }.awaitAll().flatten() }
    }

    /** 全源分片拼整墙：按片名归一去重，保留源到达序 */
    fun mergeShards(pool: List<VodSource>, cat: AggCat?): List<VodSummary> {
        val all = ArrayList<VodSummary>()
        for (s in pool) shards.getRaw(key(s, cat))?.v?.let { all.addAll(it) }
        val seen = HashSet<String>()
        return all.filter { val k = CmsParser.normalizeTitle(it.name); k.isNotEmpty() && seen.add(k) }
    }

    /** 首轮覆盖写分片（深度封顶） */
    fun putShard(s: VodSource, cat: AggCat?, items: List<VodSummary>) {
        if (items.isNotEmpty()) shards.set(key(s, cat), dedupById(items).take(DEPTH))
    }

    /** 续轮追加分片：dedupById 防同页重复回 + 800 封顶（web aggLoadMore 内联逻辑） */
    fun appendShard(s: VodSource, cat: AggCat?, more: List<VodSummary>) {
        if (more.isEmpty()) return
        val old = shards.getRaw(key(s, cat))?.v ?: emptyList()
        shards.set(key(s, cat), dedupById(old + more).take(DEPTH))
    }

    fun dedupById(items: List<VodSummary>): List<VodSummary> {
        val ids = HashSet<String>()
        return items.filter { ids.add("${it.sourceUrl}|${it.vodId}") }
    }

    /** 片名归一键（队列化判重用） */
    fun nameKey(it: VodSummary): String = CmsParser.normalizeTitle(it.name)

    /** 首绘年降序比较（同年保持源返回更新时间序） */
    fun yearDesc(items: List<VodSummary>): List<VodSummary> =
        items.mapIndexed { i, it -> it to i }.sortedWith(
            compareByDescending<Pair<VodSummary, Int>> { it.first.year?.takeWhile { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                .thenBy { it.second },
        ).map { it.first }

    /**
     * 后台预取其余分类分片（只进缓存不渲染）：延迟 2s 让位当前墙，并发 2 低优先；
     * alive 由调用方给墙轮次 token——切类/换源/离页即停。
     */
    suspend fun prefetch(repo: CmsRepository, pool: List<VodSource>, ttl: Long, alive: () -> Boolean) {
        delay(2000)
        val cats: List<AggCat?> = listOf(null) + AGG_CATS
        for (c in cats) {
            if (!alive()) return
            coroutineScope {
                val sem = Semaphore(2)
                pool.forEach { s ->
                    launch {
                        sem.withPermit {
                            if (!alive() || fresh(s, c, ttl)) return@withPermit
                            val got = runCatching { fetchSource(repo, s, c, 1) }.getOrDefault(emptyList())
                            putShard(s, c, got)
                        }
                    }
                }
            }
        }
    }

    /** 设置页「清理缓存」钩子：聚合分片+分类树，内存与磁盘双清（web purgeCache('aggs:') 等价） */
    fun clearAll() { shards.clear(); typesC.clear() }
}
