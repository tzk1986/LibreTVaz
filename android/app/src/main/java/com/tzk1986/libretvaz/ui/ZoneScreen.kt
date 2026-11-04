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
import androidx.compose.ui.focus.FocusRequester
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
 * 顶栏=点播同款 FuncCard+网络·时钟；测速后置：进墙只回填 ZSP 7 天缓存角标，拉流毕才采样；
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
    var cat by remember { mutableStateOf(-1) } // -1=最新；0..2=Zone.ZONE_CATS（客户端过滤，不重拉）
    var flt by remember { mutableStateOf(-1) } // -1=全部；0..n=Zone.ZONE_FILTERS（与一级分类取交集）
    var selUrl by remember { mutableStateOf(settings.zoneSrc) } // 空=聚合源；非空=单专区源
    var showSrc by remember { mutableStateOf(false) } // 源选择弹窗
    var wall by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) }
    var nonce by remember { mutableIntStateOf(0) } // 🔄刷新钮轮次 token：清本池分片后重跑墙
    var speedSeq by remember { mutableIntStateOf(0) }
    var aggWin by remember { mutableIntStateOf(240) } // S4 窗口渲染：同点播墙，哨兵先本地展开零请求
    val tags = remember { mutableStateMapOf<String, PerfCore.SpeedTag>() } // 「源|id」→角标
    var poolCache by remember { mutableStateOf(store.vodSources().filter { it.isAdult }) }
    // B10 无限下拉（与点播聚合墙同引擎）：分片秒开+续轮增量+哨兵回焦连按
    var aggPage by remember { mutableIntStateOf(1) }
    var aggMore by remember { mutableStateOf(false) }
    var aggLoadingMore by remember { mutableStateOf(false) }
    var sentinelFocused by remember { mutableStateOf(false) }
    val sentinelFr = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

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
        val pool = zonePoolNow()
        val ttl = aggTtl()
        wall = emptyList(); aggPage = 1; aggMore = false; aggLoadingMore = false
        if (pool.isEmpty()) { loading = false; return@LaunchedEffect }
        loading = true
        val drawn = HashSet<String>()
        var firstDrawn = false
        fun mergeFrom(): Boolean {
            val merged = Agg.mergeShards(pool, null)
            if (merged.isEmpty()) return false
            if (!firstDrawn) { firstDrawn = true; wall = Agg.yearDesc(merged) }
            else {
                val fresh = merged.filter { drawn.add(Agg.nameKey(it)) }
                if (fresh.isNotEmpty()) wall = wall + fresh
            }
            merged.forEach { drawn.add(Agg.nameKey(it)) }
            // 专区墙片名+提取番号入专区词库（用户 2026-11-04 番号搜索：编号也进简拼提示候选）
            VocabPools.get(context).addWords(merged.map { it.name } + merged.mapNotNull { CmsParser.codeRaw(it.name) }, true)
            aggMore = true
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

    // S4 无限下拉：各源同分类 pg+1 追加进分片（dedupById+800 封顶）再整墙重并，增量判底
    fun aggLoadMore() {
        if (!aggMore || aggLoadingMore) return
        if (aggPage >= Agg.FUSE_ROUND) { aggMore = false; return }
        val refocus = sentinelFocused
        val my = wallSeq
        val pg = aggPage + 1
        val pool = zonePoolNow()
        aggLoadingMore = true
        scope.launch {
            coroutineScope {
                pool.forEach { s ->
                    launch {
                        val more = runCatching { Agg.fetchSource(repo, s, null, pg) }.getOrDefault(emptyList())
                        Agg.appendShard(s, null, more)
                    }
                }
            }
            aggLoadingMore = false
            if (my != wallSeq) return@launch
            aggPage = pg
            val all = Agg.mergeShards(pool, null)
            val onWall = wall.mapTo(HashSet()) { Agg.nameKey(it) }
            val added = all.filter { onWall.add(Agg.nameKey(it)) }
            aggMore = added.isNotEmpty()
            if (added.isNotEmpty()) {
                wall = wall + added
                VocabPools.get(context).addWords(added.map { it.name } + added.mapNotNull { CmsParser.codeRaw(it.name) }, true)
                speedTest()
                if (refocus) {
                    delay(80) // 追加重绘后回焦哨兵，支持连按 OK 无限下刷
                    runCatching { sentinelFr.requestFocus() }
                }
            }
        }
    }

    // 客户端过滤（零重拉）：wall=latest 全池，shown=一级分类∩二级筛选；窗口渲染作用于过滤结果
    val shown = remember { derivedStateOf { Zone.filterPool(wall, cat, flt) } }
    LaunchedEffect(cat, flt) { aggWin = 240 }
    val expandable = shown.value.size > aggWin
    val view = if (expandable) shown.value.take(aggWin) else shown.value
    fun aggMoreOrExpand() { if (expandable) aggWin += 240 else aggLoadMore() }
    AutoLoadMore(gridState, view.size, aggLoadingMore, expandable || aggMore) { aggMoreOrExpand() }

    /** 🔄 刷新钮（web zoneRefresh 等价）：只清本池源的聚合分片，当前墙立即重拉 */
    fun refreshZone() {
        Agg.purgePoolShards(zonePoolNow().map { it.url })
        Toast.makeText(context, "已清资源口缓存，重新拉取中…", Toast.LENGTH_SHORT).show()
        nonce++
    }

    // 紧凑化对齐点播首页（用户 2026-11-04）：横向 16 + 上 8/下 4、行距 4dp，纵向全让海报墙
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 顶栏（对齐点播同款）：单行 FuncCard 小方块横滚 + 右网络/时钟，替代大标题+OutlinedButton 行
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
                    val selName = poolCache.firstOrNull { it.url == selUrl }?.name
                    FuncCard(icon = null, name = if (selUrl.isEmpty()) "源:聚合▾" else "源:${selName?.take(6) ?: "聚合"}▾", onClick = { showSrc = true })
                }
                item { FuncCard(icon = null, name = "刷新", onClick = { refreshZone() }) }
                item { FuncCard(icon = null, name = "搜索", onClick = onSearch) }
                item { FuncCard(icon = null, name = "测速", onClick = { speedTest(true) }) }
                item { FuncCard(icon = null, name = "历史", onClick = onHist) }
                item { FuncCard(icon = null, name = "收藏", onClick = onKeep) }
            }
            NetClockPair()
        }
        if (poolCache.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无预置源：恢复预置或导入订阅后在此聚合海报墙", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = {
                    settings.zoneSeedVer = "" // 清标记重种（web zoneReseed）
                    reloadPool()
                }) { Text("↻ 恢复预置源") }
            }
            return@Column
        }
        // 专区专用过滤（与首页隔离）：一级分类行 + 二级成人细分筛选行，全部客户端过滤零重拉
        CompactChips {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = cat == -1, onClick = { cat = -1 }, label = { Text("最新") })
                    }
                    itemsIndexed(Zone.ZONE_CATS) { i, c ->
                        FilterChip(selected = cat == i, onClick = { cat = i }, label = { Text(c.key) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = flt == -1, onClick = { flt = -1 }, label = { Text("全部") })
                    }
                    itemsIndexed(Zone.ZONE_FILTERS) { i, f ->
                        FilterChip(selected = flt == i, onClick = { flt = i }, label = { Text(f.key) })
                    }
                }
            }
        }
        // 提示行整体撤销（对齐点播 2026-11-03 定稿：加载态由墙尾哨兵呈现，空间全让海报墙）；错误兜底保留
        if (!loading && wall.isEmpty()) {
            Text("专区源未返回数据：可点「测速」逐条验证起播，或稍后返回重进", color = MaterialTheme.colorScheme.error)
        } else if (!loading && wall.isNotEmpty() && shown.value.isEmpty()) {
            Text("当前分类/筛选暂无命中（全池 ${wall.size} 部，可切「最新+全部」或继续下拉加深数据）",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(view, key = { "${it.sourceUrl}|${it.vodId}" }) { it ->
                ZonePoster(
                    item = it,
                    tag = tags[zspKey(it)],
                    onClick = { onPick(it) },
                    onSrc = { // 点来源标=切到该源继续查同类（筛选保留，用户 2026-11-04）
                        selUrl = it.sourceUrl; settings.zoneSrc = it.sourceUrl; showSrc = false
                    },
                )
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
        AlertDialog(
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
        modifier = Modifier.fillMaxWidth(),
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
private fun ZonePoster(item: VodSummary, tag: PerfCore.SpeedTag?, onClick: () -> Unit, onSrc: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
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
            Text(
                text = item.sourceName,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onSrc)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
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
