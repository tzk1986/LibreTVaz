package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.KeyDedupe
import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.data.perf.Region
import kotlinx.coroutines.Dispatchers
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
 * 地区预设（聚合）=纯客户端过滤：取数不随预设改道、复用「全部」分片零重拉（用户 2026-11-04 定稿）；
 * 墙拉起后低并发预取其余分类（只进分片不渲染，切类秒开）。
 */
object Agg {
    const val POOL_SIZE = 6            // 第一波并发数：首墙只等最快那批源，出图速度不变
    const val POOL_LIMIT = 12          // 总池上限：7~12 名第二波后台补拉增数据量（有意偏离 web 固定 top6，用户 2026-11-03 要求）
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

    // 单源父类聚合子类结果缓存（web `mrg:` 分片等价）：localStorage 常驻+7 天硬清兜底，此处 TTL 同 7 天
    private val mrgC = DiskBacked(MemCache<List<VodSummary>>(64), "mrg:", CmsCodecs::encSummaries, CmsCodecs::decSummaries)

    fun key(s: VodSource, cat: AggCat?): String =
        "aggs:" + s.url + "|" + if (cat == null) "latest"
        else cat.key + if (cat.rlabs.isNotEmpty()) "@" + cat.rlabs.joinToString(",") else ""

    /** 健康 ms 排序取池：死源不排除自然沉底，未测按中性 4000ms；默认 top12=两波（首波前 6） */
    fun poolOf(sources: List<VodSource>, limit: Int = POOL_LIMIT, msOf: (VodSource) -> Long): List<VodSource> =
        sources.sortedBy { msOf(it) }.take(limit)

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
        return types.any { it.pid == p.id && it.id != p.id && Region.labels(it.name).any { l -> cat.rlabs.contains(l) } }
    }

    /** 该源在 cat 下要拉的子集：地区预设取命中子类前 4（无则回落父类混拉）；常规取前 3 子类（无子类用父类） */
    fun subCatsFor(types: List<VodType>, cat: AggCat): List<VodType> {
        val p = topOf(types, cat) ?: return emptyList()
        val kids = types.filter { it.pid == p.id && it.id != p.id }
        return if (cat.rlabs.isNotEmpty()) {
            val reg = kids.filter { c -> Region.labels(c.name).any { l -> cat.rlabs.contains(l) } }
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

    /** 片名归一去重（web dedupByName：空键剔除，保留首见） */
    fun dedupByName(items: List<VodSummary>): List<VodSummary> {
        val seen = HashSet<String>()
        return items.filter { val k = nameKey(it); k.isNotEmpty() && seen.add(k) }
    }

    // ===== 单源父类聚合子类（web applyTabMerged/loadMerged/presetKeyForTab/presetIds 等价） =====

    /** mrg: 缓存键（web L1601）：presetLabel 仅服务端预设合并（三级 a）时参与 */
    fun mrgKey(url: String, tabId: String, area: String, year: String, lang: String, presetLabel: String): String =
        "mrg:$url|$tabId|$area|$year|$lang|$presetLabel"

    /** SWR 回读（web cacheGet 无 TTL 判定：旧数据也先上屏） */
    fun mrgPeek(key: String): List<VodSummary>? = mrgC.getRaw(key)?.v
    fun mrgPut(key: String, items: List<VodSummary>) = mrgC.set(key, items)

    /** 沿 pid 上溯根（hop≤5），根名+本名匹配预设族 → Region.PRESETS 键（web presetKeyForTab 单源分支） */
    fun presetKeyOf(tab: VodType?, types: List<VodType>): String? {
        val t = tab ?: return null
        var cur: VodType? = t
        var root = t
        var hop = 0
        while (cur != null && hop++ <= 5) {
            root = cur
            val p = cur.pid
            val next = if (p.isNotEmpty() && p != "0" && p != "null") types.find { it.id == p } else null
            cur = next
        }
        val nm = (root.name) + "|" + t.name
        return when {
            Regex("电影|影片").containsMatchIn(nm) && !Regex("电视剧|连续剧").containsMatchIn(nm) -> "电影"
            Regex("电视剧|连续剧|剧集").containsMatchIn(nm) -> "电视剧"
            Regex("综艺|脱口秀|真人秀").containsMatchIn(nm) -> "综艺"
            else -> null
        }
    }

    /** t 是否处于 tab 子树（沿 pid 上溯 hop≤6，web isDescendantOfTab） */
    private fun isDescendant(t: VodType, tab: VodType, types: List<VodType>): Boolean {
        var c: VodType? = t
        var hop = 0
        while (c != null && hop++ <= 6) {
            if (c.id == tab.id) return true
            val n = c
            c = types.find { it.id == n.pid }
        }
        return false
    }

    /** R8 三级 (a)：tab 子树中名字带预设地区信号的子分类；无命中 null → 客户端 Region.of 兜底（web presetIds） */
    fun presetIdsOf(labs: List<String>, tab: VodType?, types: List<VodType>): List<VodType>? {
        val t = tab ?: return null
        if (labs.isEmpty()) return null
        val hit = types.filter {
            it.id != t.id && isDescendant(it, t, types) && Region.labels(it.name).any { l -> labs.contains(l) }
        }
        return hit.ifEmpty { null }
    }

    /** 父类聚合并发拉取（web pMap limit=6）：逐子类 t=kid.id 拼 area/year/lang；单类失败=空页不炸整页 */
    suspend fun fetchMerged(
        repo: CmsRepository, s: VodSource, kids: List<VodType>,
        area: String, year: String, lang: String, pg: Int,
    ): List<List<VodSummary>> = coroutineScope {
        val sem = Semaphore(6)
        kids.map { k ->
            async(Dispatchers.IO) {
                sem.withPermit { repo.listFiltered(s, k.id, area, year, lang, pg).first }
            }
        }.awaitAll()
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

    /** 设置页「清理缓存」钩子：聚合分片+分类树+父类聚合，内存与磁盘双清（web purgeCache 等价） */
    fun clearAll() { shards.clear(); typesC.clear(); mrgC.clear() }

    /** 点播刷新钮（web vodRefresh L1298 等价）：聚合=清全部 aggs: 分片；单源=清该源分类树+mrg: 父类聚合分片 */
    fun purgeWallShards() = shards.clear()
    fun purgeTypesOf(url: String) = typesC.remove("types:$url")
    fun purgeMrgOf(url: String) = mrgC.removePrefix("mrg:$url|")

    /** 资源口刷新钮（web zoneRefresh 等价）：只清本池源的聚合分片（详情/测速/搜索缓存不动） */
    fun purgePoolShards(urls: List<String>) = urls.forEach { shards.removePrefix("aggs:$it|") }
}
