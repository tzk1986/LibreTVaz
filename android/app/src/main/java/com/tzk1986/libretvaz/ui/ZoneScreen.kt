package com.tzk1986.libretvaz.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.LineSpeed
import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 墙内条目起播速度缓存（web adsp/zsp 语义，7 天）；B11 双层：内存+磁盘；internal 供换源弹框复用（同「源|id」语义） */
internal val ZSP = DiskBacked(MemCache<Long>(600), "zsp:", CmsCodecs::encLong, CmsCodecs::decLong)
internal const val ZSP_TTL = 7 * 24 * 3600_000L

/** 专区条目详情缓存（测速取首线路首集用，6h）；键与角标分离（detail: 命名空间，TTL 不同不得混用） */
internal val ZDETAIL = DiskBacked(MemCache<VideoDetail>(300), "detail:", CmsCodecs::encDetail, CmsCodecs::decDetail)
internal const val ZDETAIL_TTL = 6 * 3600_000L

/** 设置页「清理缓存」钩子：专区角标测速+条目详情（内存+磁盘双清，web cache_purge 的 zsp 部分） */
internal fun clearZoneCaches() { ZSP.clear(); ZDETAIL.clear() }

/**
 * 资源口 v2（用户 2026-11-04 重设计，web 蓝本之外的增强；与首页点播 AGG_CATS/地区筛选完全隔离各用各的）：
 * ①源选择钮：默认「聚合源」（专区池按健康 ms 取前 12 两波并发），可切单个专区源；选择持久化（settings.zoneSrc），
 *   海报左下「来源标」点按=快速切到该源且保留当前分类/筛选；
 * ②单池分批拉取：墙只拉各源 latest 分片（不再按分类换 tab 服务端重拉），二波后后台低并发续轮加深（2..7 轮，
 *   无新增即停），聚合池越滚越大；
 * ③纯客户端过滤：一级分类行=最新/有码/无码/动漫，二级筛选行=人妻/制服/萝莉/…（单选+交集，Zone.ZONE_CATS/
 *   ZONE_FILTERS 关键词匹配 typeName/片名），切类切筛选零重拉零请求（沿用地区筛选 2026-11-04 定稿思路）；
 *   二级行默认折叠：再点当前一级分类展开/收起，选中具体筛选即收起（对齐首页 showFilters，用户 2026-11-04）；
 * 顶栏=点播同款 FuncCard+网络·时钟，做成墙 item0 随下拉逐渐隐去、分类 tab stickyHeader 吸顶、回顶复原
 * （用户 2026-11-04 滚动收缩需求）；测速后置：进墙只回填 ZSP 7 天缓存角标，拉流毕才采样；
 * 刷新清本池分片重跑；找片钮已去除（用户 2026-11-04：与「搜索」功能重复，统一走搜索→CollectScreen 番号秒查链路）；
 * 入口=设置→影视仓实验室。公开克隆无站单=空态「恢复预置」。
 */
@Composable
fun ZoneScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onKeep: () -> Unit,
    onHist: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CmsRepository() }
    val health = remember { HealthStore(context) }
    val settings = remember { SettingsStore(context) }
    // 用户 2026-11-04 修复返回路径：分类/筛选选择提升到进程级（进详情返回不再复位「最新」）
    var cat by WallMem.zoneCat  // -1=最新；0..2=Zone.ZONE_CATS（客户端过滤，不重拉）
    var flt by WallMem.zoneFlt  // -1=全部；0..n=Zone.ZONE_FILTERS（与一级分类取交集）
    var showSub by remember { mutableStateOf(false) } // 二级筛选行折叠（对齐首页 showFilters：再点当前一级=展开/收起，选中筛选即收起）
    var selUrl by remember { mutableStateOf(settings.zoneSrc) } // 空=聚合源；非空=单专区源
    var showSrc by remember { mutableStateOf(false) } // 源选择弹窗
    // 用户 2026-11-09 #130：返回重建首帧直接种回 keep 墙（同 browseKeep 走查 #11——若等 effect 播种，
    // 空墙先量一次会把 gridState 起始滚动钳回 0）；key=selUrl（聚合=空串/单源=url）
    var wall by remember { mutableStateOf(WallMem.zoneKeep.value?.takeIf { it.first == selUrl && it.second.isNotEmpty() }?.second ?: emptyList<VodSummary>()) }
    // 方案A 同构（用户 2026-11-06 资源口）：筛选态后台定向增补的条目池（zone:标签 分片并入，随墙重建清空）
    var zextra by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) }
    var nonce by remember { mutableIntStateOf(0) } // 🔄刷新钮轮次 token：清本池分片后重跑墙
    var speedSeq by remember { mutableIntStateOf(0) }
    // #130 同 BrowseScreen 终审 #2：返回重建按 keep 墙体量播种，防已展开的长墙被 240 截断回缩（滚动恢复须视图片段仍在）
    var aggWin by remember { mutableIntStateOf(maxOf(240, WallMem.zoneKeep.value?.takeIf { it.first == selUrl }?.second?.size ?: 0)) }
    val tags = remember { mutableStateMapOf<String, PerfCore.SpeedTag>() } // 「源|id」→角标
    var poolCache by remember { mutableStateOf(store.vodSources().filter { it.isAdult }) }
    // B10 无限下拉（与点播聚合墙同引擎）：分片秒开+续轮增量+哨兵回焦连按
    var aggPage by remember { mutableIntStateOf(1) }
    var aggMore by remember { mutableStateOf(false) }
    var aggLoadingMore by remember { mutableStateOf(false) }
    var sentinelFocused by remember { mutableStateOf(false) }
    val sentinelFr = remember { FocusRequester() }
    // #130 滚动还原+回存（同 browseScroll 形制）：首帧用记忆 (index,offset)；
    // 换 key 后首发=同一 gridState 旧墙滚动位瞬态值，跳过防污染新 key 恢复点（minor#6 同构）
    val zScr0 = remember { WallMem.zoneScroll.value?.takeIf { it.first == selUrl }?.let { it.second to it.third } ?: (0 to 0) }
    val gridState = rememberLazyGridState(zScr0.first, zScr0.second)
    LaunchedEffect(selUrl) {
        var first = true
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect {
                if (first) { first = false; return@collect }
                WallMem.zoneScroll.value = Triple(selUrl, it.first, it.second)
                delay(250) // 简易节流：延迟期间滚动中间态被 conflated 丢弃
            }
    }

    fun aggTtl() = CacheTiers.TIERS[settings.cacheTier].agg

    /** 当前墙取数池：聚合=健康 ms 前 12 两波；单源=只拉选中源（latest 分片同键复用聚合结果） */
    fun zonePoolNow(): List<VodSource> =
        if (selUrl.isNotEmpty()) poolCache.filter { it.url == selUrl }
        else Agg.poolOf(poolCache) { s -> Agg.healthMs(health, s) }

    // 分层返回：未滚到顶→回顶；到顶→回设置页（子页永不直接退 app）
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 } }
    BackHandler {
        when {
            !atTop -> scope.launch { gridState.animateScrollToItem(0) }
            else -> onBack()
        }
    }

    fun zspKey(it: VodSummary) = "zsp:${PerfCore.norm(it.sourceUrl)}|${it.vodId}"
    fun zDetailKey(it: VodSummary) = "detail:${PerfCore.norm(it.sourceUrl)}|${it.vodId}"

    /** 条目起播速度：详情→首线路首集 Range 采样；并发 3、年新的优先取前 60、7 天缓存秒回；force=测速钮强制重采样 */
    fun speedTest(force: Boolean = false) {
        if (!settings.lineSpeed) return
        val s = speedSeq + 1; speedSeq = s
        scope.launch {
            val targets = Agg.yearDesc(wall).take(60) // web sortByYearDesc(Vod.items).slice(0,60)：新片优先测
            val sem = Semaphore(3)
            coroutineScope {
                targets.forEach { it ->
                    launch(Dispatchers.IO) {
                        if (s != speedSeq) return@launch
                        val k = zspKey(it)
                        val kb = (if (force) null else ZSP.get(k, ZSP_TTL)) ?: runCatching {
                            sem.withPermit {
                                if (s != speedSeq) return@withPermit -1L
                                val src = VodSource(it.sourceName, it.sourceUrl, isAdult = true)
                                val dk = zDetailKey(it)
                                val d = ZDETAIL.get(dk, ZDETAIL_TTL) ?: repo.detail(src, it.vodId)
                                d?.let { dd -> ZDETAIL.set(dk, dd) }
                                val link = d?.lines?.firstOrNull()?.list?.firstOrNull()?.link
                                if (link == null) 0L else LineSpeed.sampleKbps(LineSpeed.segOrSelf(link))
                            }
                        }.getOrDefault(-1L)
                        if (s != speedSeq) return@launch
                        ZSP.set(k, kb)
                        PerfCore.lineSpeedTag(kb)?.let { t -> tags[k] = t }
                    }
                }
            }
        }
    }

    // 进页补种+对齐（版本闸门；站单空=静默跳过）
    fun reloadPool() {
        Zone.seedIfAbsent(context, store)
        store.alignZone()
        poolCache = store.vodSources().filter { it.isAdult }
        // 选中源已被删除→回落聚合
        if (selUrl.isNotEmpty() && poolCache.none { it.url == selUrl }) { selUrl = ""; settings.zoneSrc = "" }
    }
    LaunchedEffect(Unit) { reloadPool() }

    /** 角标缓存回填（测速后置配套）：只读 ZSP 7 天缓存，命中秒显、零采样零带宽占用 */
    fun fillTagsFromCache() {
        if (!settings.lineSpeed) return
        scope.launch(Dispatchers.IO) {
            val targets = Agg.yearDesc(wall).take(60)
            val filled = ArrayList<Pair<String, PerfCore.SpeedTag>>()
            targets.forEach { it ->
                val k = zspKey(it)
                if (!tags.containsKey(k)) {
                    val kb = ZSP.get(k, ZSP_TTL)
                    if (kb != null) PerfCore.lineSpeedTag(kb)?.let { t -> filled.add(k to t) }
                }
            }
            filled.forEach { (k, t) -> tags[k] = t }
        }
    }

    // latest 单池墙（web runAggWall 等价改造）：陈旧分片秒开 → 首波逐源增量上墙 → 二波 → 缓存角标回填+起测 → 后台续轮加深；
    // 分类/筛选为纯客户端过滤不在键内——切类切筛选零重拉（用户 2026-11-04：过滤模块只消费本池）
    LaunchedEffect(nonce, selUrl, poolCache) {
        reloadPool()
        val my = wallSeq + 1
        wallSeq = my
        tags.clear()
        // #130：key 命中=返回重建（wall 已在 remember 初值播种），恢复原墙不清空——
        // 🔄清槽/换源/池变更自然走干净首屏；drawn/firstDrawn 播种后本轮 mergeFrom 走增量分支只补新片名
        val keepHit = WallMem.zoneKeep.value?.takeIf { it.first == selUrl && it.second.isNotEmpty() }?.second
        val pool = zonePoolNow()
        val ttl = aggTtl()
        wall = keepHit ?: emptyList(); zextra = emptyList(); aggPage = 1; aggMore = false; aggLoadingMore = false
        if (pool.isEmpty()) { loading = false; return@LaunchedEffect }
        loading = keepHit == null
        val drawn = if (keepHit != null) HashSet(keepHit.map { Agg.nameKey(it) }) else HashSet()
        var firstDrawn = keepHit != null
        fun mergeFrom(): Boolean {
            val merged = Agg.mergeShards(pool, null)
            if (merged.isEmpty()) return false
            if (!firstDrawn) { firstDrawn = true; wall = Agg.yearDesc(merged) }
            else {
                val fresh = merged.filter { drawn.add(Agg.nameKey(it)) }
                if (fresh.isNotEmpty()) wall = wall + fresh
            }
            merged.forEach { drawn.add(Agg.nameKey(it)) }
            // 专区墙片名+提取番号入专区词库（用户 2026-11-04 番号搜索：编号也进简拼提示候选）；终审 #4：mergeFrom 在 Main，派生移后台
            scope.launch(Dispatchers.Default) { VocabPools.get(context).addWords(merged.map { it.name } + merged.mapNotNull { CmsParser.codeRaw(it.name) }, true) }
            aggMore = true
            WallMem.zoneKeep.value = selUrl to wall // #130：wall 每次变更后同步回存
            return true
        }
        mergeFrom()
        val need = pool.take(Agg.POOL_SIZE).filterNot { Agg.fresh(it, null, ttl) }
        coroutineScope {
            need.forEach { s ->
                launch {
                    val got = runCatching { Agg.fetchSource(repo, s, null, 1) }.getOrDefault(emptyList())
                    Agg.putShard(s, null, got)
                    if (my == wallSeq) mergeFrom()
                }
            }
        }
        if (my != wallSeq) return@LaunchedEffect
        loading = false
        if (wall.isNotEmpty()) {
            fillTagsFromCache() // 7 天内已测条目秒显角标；采样测速整体后置（用户 2026-11-04：海报墙优先不抢带宽）
            // 二波补拉（7~12 名）：不阻塞交互，落分片后追加墙尾；毕起缓存回填+采样，再起后台续轮加深
            val back = pool.drop(Agg.POOL_SIZE).filterNot { Agg.fresh(it, null, ttl) }
            launch {
                coroutineScope {
                    back.forEach { s ->
                        launch {
                            val got = runCatching { Agg.fetchSource(repo, s, null, 1) }.getOrDefault(emptyList())
                            Agg.putShard(s, null, got)
                            if (my == wallSeq) mergeFrom()
                        }
                    }
                }
                if (my == wallSeq) {
                    fillTagsFromCache()
                    speedTest()
                    // 聚合加深（用户 2026-11-04：拉更多数据进全池）：latest pg 2..7 逐轮，并发 3，无新增即停
                    for (pg in 2..7) {
                        if (my != wallSeq || aggLoadingMore) break
                        val before = wall.size
                        coroutineScope {
                            val sem = Semaphore(3)
                            pool.forEach { s ->
                                launch(Dispatchers.IO) {
                                    sem.withPermit {
                                        if (my != wallSeq) return@withPermit
                                        val more = runCatching { Agg.fetchSource(repo, s, null, pg) }.getOrDefault(emptyList())
                                        Agg.appendShard(s, null, more)
                                    }
                                }
                            }
                        }
                        if (my != wallSeq) break
                        aggPage = pg
                        mergeFrom()
                        if (wall.size == before) break // 全池无新增=自然到底
                    }
                }
            }
        }
    }

    // 方案A 同构（用户 2026-11-06 资源口，铁律放宽为「切类/切筛选零阻塞、允许后台定向增补」）：
    // 选中一级/二级筛选时后台拉各源「类名命中标签关键词」的顶层类页进 zone:标签 独立分片（与 latest 池隔离、
    // 零改道零清空），逐源到达并入 zextra；首屏仍是 latest 池客户端过滤秒显；命中该类优先、并发 2 低优先
    LaunchedEffect(cat, flt, nonce, selUrl, poolCache) {
        val ztags = listOfNotNull(Zone.ZONE_CATS.getOrNull(cat), Zone.ZONE_FILTERS.getOrNull(flt))
        if (ztags.isEmpty()) return@LaunchedEffect
        val my = wallSeq
        val pool = zonePoolNow()
        if (pool.isEmpty()) return@LaunchedEffect
        suspend fun mergeZ(tag: Zone.ZoneTag) {
            val merged = withContext(Dispatchers.IO) { Agg.mergeZoneShards(pool, tag) }
            if (merged.isEmpty()) return
            val fresh = withContext(Dispatchers.Main) {
                val seen = (wall + zextra).mapTo(HashSet()) { Agg.nameKey(it) }
                val f = merged.filter { seen.add(Agg.nameKey(it)) }
                if (f.isNotEmpty()) zextra = zextra + f
                f
            }
            if (fresh.isNotEmpty()) launch(Dispatchers.Default) {
                VocabPools.get(context).addWords(fresh.map { it.name } + fresh.mapNotNull { CmsParser.codeRaw(it.name) }, true)
            }
        }
        for (tag in ztags) {
            if (my != wallSeq) return@LaunchedEffect
            mergeZ(tag) // 已有定向分片秒併（二次进同 chip 零等待）
            val caps = pool.mapNotNull { s ->
                runCatching { if (Agg.topMatches(Agg.typesOf(repo, s), tag.kw).isNotEmpty()) s.url else null }.getOrNull()
            }.toSet()
            coroutineScope {
                val sem = Semaphore(2)
                pool.sortedByDescending { if (it.url in caps) 1 else 0 }.filterNot { Agg.freshZone(it, tag, aggTtl()) }.forEach { s ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            if (my != wallSeq) return@withPermit
                            val got = runCatching { Agg.fetchZoneTag(repo, s, tag, 1) }.getOrDefault(emptyList())
                            Agg.putZoneShard(s, tag, got)
                            if (my == wallSeq) mergeZ(tag)
                        }
                    }
                }
            }
        }
    }

    // S4 无限下拉：各源同分类 pg+1 追加进分片（dedupById+800 封顶）。逐源到达即增量上墙+单源 9s 封顶
    // （用户 2026-11-04：「显示加载中要等很久」——不再整轮等最慢源，快源 1~3s 先出图）
    fun aggLoadMore() {
        if (!aggMore || aggLoadingMore) return
        if (aggPage >= Agg.FUSE_ROUND) { aggMore = false; return }
        val refocus = sentinelFocused
        val my = wallSeq
        val pg = aggPage + 1
        val pool = zonePoolNow()
        val ztags = listOfNotNull(Zone.ZONE_CATS.getOrNull(cat), Zone.ZONE_FILTERS.getOrNull(flt))
        aggLoadingMore = true
        scope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            pool.forEach { s ->
                launch(Dispatchers.IO) {
                    // 过滤态续轮同点播侧方案A：优先定向拉标签命中顶层类 pg 页；本源无命中类→回落 latest 续页
                    val fz = if (ztags.isEmpty()) emptyList() else runCatching {
                        kotlinx.coroutines.withTimeout(9_000) { ztags.map { t -> t to Agg.fetchZoneTag(repo, s, t, pg) } }
                    }.getOrDefault(emptyList())
                    if (fz.any { it.second.isNotEmpty() }) fz.forEach { (t, got) -> if (got.isNotEmpty()) Agg.appendZoneShard(s, t, got) }
                    else {
                        val more = runCatching {
                            kotlinx.coroutines.withTimeout(9_000) { Agg.fetchSource(repo, s, null, pg) }
                        }.getOrDefault(emptyList())
                        if (more.isNotEmpty()) Agg.appendShard(s, null, more)
                    }
                    if (my != wallSeq) return@launch
                    val all = Agg.mergeShards(pool, null) // 走查 #4 同构：读盘合并移出 Main
                    val zm = if (ztags.isEmpty()) emptyList() else ztags.flatMap { Agg.mergeZoneShards(pool, it) }
                    withContext(Dispatchers.Main) {
                        val onWall = (wall + zextra).mapTo(HashSet()) { Agg.nameKey(it) }
                        val added = (all + zm).filter { onWall.add(Agg.nameKey(it)) }
                        if (added.isNotEmpty()) {
                            wall = wall + added
                            launch(Dispatchers.Default) { VocabPools.get(context).addWords(added.map { it.name } + added.mapNotNull { CmsParser.codeRaw(it.name) }, true) } // 终审 #4：词库派生移出 Main
                            WallMem.zoneKeep.value = selUrl to wall // #130：续轮追加同样回存
                        }
                    }
                    if (done.incrementAndGet() == pool.size) {
                        aggLoadingMore = false
                        aggPage = pg
                        // 空轮不判到底（用户 2026-11-04：下拉要继续拉更多——CMS 尾页常整页重复、后页反有新数据；
                        // 仅当全池分片都到 800 封顶或触 FUSE 轮才停）；分类/筛选 chip 下同型自链，10 轮封顶（点播侧同日走查）
                        aggMore = Agg.aggMoreContinue(aggPage, cat >= 0 || flt >= 0,
                            pool.any { (Agg.shards.getRaw(Agg.key(it, null))?.v?.size ?: 0) < Agg.DEPTH })
                        speedTest()
                        if (refocus) {
                            delay(80) // 追加重绘后回焦哨兵，支持连按 OK 无限下刷
                            runCatching { sentinelFr.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    // 客户端过滤（零重拉）：wall=latest 全池+zextra 定向增补（片名并集兜底去重），shown=一级分类∩二级筛选；窗口渲染作用于过滤结果
    val shown = remember { derivedStateOf { Zone.filterPool(Agg.dedupByName(wall + zextra), cat, flt) } }
    // C32（审计 2026-11-11）：首轮组合=返回重建播种时刻，无条件置 240 会盖掉上面
    // maxOf(240, keep) 种子→已展开长墙被截断回缩（同 BrowseScreen 终审#2 病根）；跳过首轮，仅后续切类/筛选复位窗口
    var winResetArmed by remember { mutableStateOf(false) }
    LaunchedEffect(cat, flt) {
        if (winResetArmed) aggWin = 240 else winResetArmed = true
    }
    val expandable = shown.value.size > aggWin
    val view = if (expandable) shown.value.take(aggWin) else shown.value
    fun aggMoreOrExpand() { if (expandable) aggWin += 240 else aggLoadMore() }
    // +2=顶栏 item+吸顶 tab 占位（绝对索引偏移）；空墙传 0 保持零触发
    AutoLoadMore(gridState, if (view.isEmpty()) 0 else view.size + 2, aggLoadingMore, expandable || aggMore) { aggMoreOrExpand() }

    /** 🔄 刷新钮（web zoneRefresh 等价）：只清本池源的聚合分片，当前墙立即重拉 */
    fun refreshZone() {
        Agg.purgePoolShards(zonePoolNow().map { it.url })
        WallMem.zoneKeep.value = null; WallMem.zoneScroll.value = null // #130：刷新=唯一整体重置口，连墙记忆+滚动一并清（nonce 不入 key，否则清不了旧墙）
        Toast.makeText(context, "已清资源口缓存，重新拉取中…", Toast.LENGTH_SHORT).show()
        nonce++
    }

    // 紧凑化对齐点播首页（用户 2026-11-04）：横向 16 + 上 8/下 4，纵向全让海报墙
    // 顶栏滚动收缩（用户 2026-11-04 新需求）：功能顶栏=墙列表 item0，随下拉 1:1 逐渐隐去、滚回顶部自然复原；
    // 分类 tab 改 stickyHeader 吸顶常驻（背景铺满防海报透出）；BackHandler「未滚顶先回顶」语义不变（index0=顶栏）
    val srcLabel = if (selUrl.isEmpty()) "源:聚合▾" else "源:${poolCache.firstOrNull { it.url == selUrl }?.name?.take(6) ?: "聚合"}▾"
    val zoneTopBar: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { FuncCard(icon = null, name = "返回", onClick = onBack) }
                item {
                    // 源选择钮：默认聚合；单源显示源名缩写（用户 2026-11-04：给出源选择，默认聚合源）
                    FuncCard(icon = null, name = srcLabel, onClick = { showSrc = true })
                }
                item { FuncCard(icon = null, name = "刷新", onClick = { refreshZone() }) }
                item { FuncCard(icon = null, name = "搜索", onClick = onSearch) }
                item { FuncCard(icon = null, name = "测速", onClick = { speedTest(true) }) }
                item { FuncCard(icon = null, name = "历史", onClick = onHist) }
                item { FuncCard(icon = null, name = "收藏", onClick = onKeep) }
            }
            NetClockPair()
        }
    }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (poolCache.isEmpty()) {
            zoneTopBar()
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无预置源：恢复预置或导入订阅后在此聚合海报墙", style = MaterialTheme.typography.bodyLarge)
                TmBtn(onClick = {
                    settings.zoneSeedVer = "" // 清标记重种（web zoneReseed）
                    reloadPool()
                }) { Text("↻ 恢复预置源") }
            }
            return@Column
        }
        // TVBox 复刻（用户 2026-11-09 需求 b）：进资源口焦点直达当前选中一级 tab 而非「最新」；
        // 触屏模式 requestFocus 自然失败无副作用；有限重试同 Browse panelFocus 模式
        val catFrs = remember { List(Zone.ZONE_CATS.size + 1) { FocusRequester() } }
        LaunchedEffect(Unit) {
            for (i in 0 until 8) {
                delay(150)
                val ok = runCatching { catFrs[(cat + 1).coerceAtLeast(0)].requestFocus() }.getOrDefault(false)
                if (ok) break
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { zoneTopBar() }
            // 专区专用过滤（与首页隔离）：一级分类行吸顶常显；二级成人细分筛选行折叠（用户 2026-11-04：像首页，
            // 再点当前一级=展开/收起，选中具体筛选即收起让位海报墙；取消/全部保持展开），全部客户端过滤零重拉
            stickyHeader {
                CompactChips {
                    // 镂空透明（用户 2026-11-04 参考图定稿）：吸顶行不留底带、全透海报墙；
                    // chip 未选中=透明描边镂空，选中=实心（WallChip 与首页共用同款）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                WallChip(
                                    selected = cat == -1,
                                    onClick = { if (cat == -1) showSub = !showSub else { cat = -1; showSub = false } },
                                    label = "最新",
                                    fr = catFrs[0],
                                )
                            }
                            itemsIndexed(Zone.ZONE_CATS) { i, c ->
                                WallChip(
                                    selected = cat == i,
                                    onClick = { if (cat == i) showSub = !showSub else { cat = i; showSub = false } },
                                    label = c.key,
                                    fr = catFrs[i + 1],
                                )
                            }
                        }
                        if (showSub) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    WallChip(selected = flt == -1, onClick = { flt = -1 }, label = "全部")
                                }
                                itemsIndexed(Zone.ZONE_FILTERS) { i, f ->
                                    WallChip(
                                        selected = flt == i,
                                        onClick = { if (flt == i) flt = -1 else { flt = i; showSub = false } },
                                        label = f.key,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 4K 投影崩溃修复（用户 2026-11-11）：撤 key=sourceUrl|vodId（池内重复，大 dp 全挂载即崩）
            items(view, contentType = { "poster" }) { it ->
                ZonePoster(
                    item = it,
                    tag = tags[zspKey(it)],
                    onClick = { onPick(it) },
                    onSrc = { // 点来源标=切到该源继续查同类（筛选保留，用户 2026-11-04）
                        selUrl = it.sourceUrl; settings.zoneSrc = it.sourceUrl; showSrc = false
                    },
                    // TVBox 对标 C2（2026-11-10）：来源标=源名展示，受「数据源显示」开关控制（关闭时切源快捷入口随之隐藏）
                    showSource = settings.showSourceName,
                )
            }
            // 错误/空态兜底（加载态由墙尾哨兵呈现，空间全让海报墙——点播 2026-11-03 定稿）
            if (view.isEmpty() && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (wall.isEmpty()) Text("专区源未返回数据：可点「测速」逐条验证起播，或稍后返回重进",
                        color = MaterialTheme.colorScheme.error)
                    else Text("当前分类/筛选暂无命中（全池 ${wall.size} 部，可切「最新+全部」或继续下拉加深数据）",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (view.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    WallSentinel(
                        loading = aggLoadingMore,
                        canMore = expandable || aggMore,
                        label = if (expandable) "⌄ 展开更多（当前过滤还有 ${shown.value.size - aggWin} 部）" else null,
                        fr = sentinelFr,
                        onFocus = { sentinelFocused = it },
                        onMore = { aggMoreOrExpand() },
                    )
                }
            }
        }
    }

    // 源选择弹窗（用户 2026-11-04）：默认聚合源，可切单个专区源；切源仅重跑取数池，分类/筛选状态保留
    if (showSrc) {
        TmDialog(
            onDismissRequest = { showSrc = false },
            confirmButton = {},
            title = { Text("选择资源口源") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item {
                        SrcRow(
                            selected = selUrl.isEmpty(),
                            name = "聚合源（健康前 ${poolCache.size.coerceAtMost(Agg.POOL_LIMIT)} 源两波并发）",
                            badge = null,
                        ) { selUrl = ""; settings.zoneSrc = ""; showSrc = false }
                    }
                    itemsIndexed(poolCache) { _, s ->
                        val h = health.of(s.url)
                        SrcRow(
                            selected = selUrl == s.url,
                            name = s.name,
                            badge = h?.let { if (it.fails >= HealthStore.DEAD_FAILS) "✗失效" else "✓${it.ms?.takeIf { m -> m > 0 } ?: ""}ms" },
                        ) { selUrl = s.url; settings.zoneSrc = s.url; showSrc = false }
                    }
                }
            },
        )
    }
}

// 源选择行（弹窗内）：当前项高亮 ●，附健康徽标
@Composable
private fun SrcRow(selected: Boolean, name: String, badge: String?, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tmFocusBorder(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected) "●" else "○", style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            badge?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ZonePoster(item: VodSummary, tag: PerfCore.SpeedTag?, onClick: () -> Unit, onSrc: () -> Unit, showSource: Boolean = true) {
    // 用户 2026-11-09 #133：与 PosterCard 同款遥控焦点=白描边+40%红罩层
    var zpFocused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.onFocusEvent { zpFocused = it.isFocused }.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
            AsyncImage(
                model = item.pic,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            tag?.let { t ->
                Text(
                    text = t.label,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(speedTagColor(t.cls).copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 更新 remarks 角标（对齐点播同款；无值不显示——用户 2026-11-04）
            item.remarks?.let { r ->
                Text(
                    text = r,
                    color = MaterialTheme.colorScheme.onSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 来源标（用户 2026-11-04）：聚合下每部可辨出处；点标=切到该源继续查当前分类/筛选
            if (showSource) Text(
                text = item.sourceName,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 走查 #14：来源标命中面过小——clickable 后先垫一层透明 padding 再 clip/background，
                // 手势界=色块+12dp 留白而色块视觉尺寸不变（foundation 1.12.1 无 minimumInteractiveTouchArea）
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clickable(onClick = onSrc)
                    .tmFocusBorder(shape = RoundedCornerShape(4.dp), focusedBg = com.tzk1986.libretvaz.ui.theme.TvColors.Red40)
                    .padding(horizontal = 6.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (zpFocused) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(com.tzk1986.libretvaz.ui.theme.TvColors.Red40)
                        .border(com.tzk1986.libretvaz.ui.theme.TvColors.StrokeW, Color.White, RoundedCornerShape(8.dp)),
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // 专区源数据脏：year 常回 "0"/空，过滤后再拼
            text = listOfNotNull(item.year?.takeIf { it.isNotBlank() && it.trim('0') != "" }, item.area?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}
