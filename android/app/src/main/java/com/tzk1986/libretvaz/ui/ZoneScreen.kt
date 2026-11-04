package com.tzk1986.libretvaz.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
 * 资源口（专区池聚合墙，web renderZone 等价+两波扩池）：专区源按健康 ms 取前 12 聚合（首波 6 先出墙、二波补量），
 * 一源返回即增量上墙（首绘年降序一次，后续未见片名按到达序追加队尾，不动已上屏条目）；
 * 顶栏=与点播首页同款紧凑小方块[返回/刷新/找片/搜索/测速/历史/收藏]+网络·时钟（用户 2026-11-04 样式对齐点播）
 * ——刷新只清本池分片重拉，找片=专区池全源搜索框，测速钮 force 重采样，墙 240 窗口本地展开；
 * 测速后置（用户 2026-11-04：海报墙优先，偏离 web 进墙即起测）：进墙只从 ZSP 7 天缓存回填旧角标不采样，
 * 全部拉流+预取结束后才起采样；并发 3、按年新的优先取前 60，失败不判死不移除。
 * 入口唯一=设置→影视仓实验室「进入」；返回落设置页。公开克隆无站单=空态「恢复预置」不报错。
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
    var cat by remember { mutableStateOf(-1) } // -1=最新；0..3=Agg.AGG_CATS
    var wall by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) }
    var nonce by remember { mutableIntStateOf(0) } // 🔄刷新钮轮次 token：清本池分片后重跑墙
    var speedSeq by remember { mutableIntStateOf(0) }
    var aggWin by remember { mutableIntStateOf(240) } // S4 窗口渲染：同点播墙，哨兵先本地展开零请求
    var showFind by remember { mutableStateOf(false) } // 🔎 找片=专区池全源搜索框（web zoneFind→openFindBox zone 路由）
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
    fun zonePoolNow() = Agg.poolOf(poolCache) { s -> Agg.healthMs(health, s) }
    fun zoneCatOf(): AggCat? = Agg.AGG_CATS.getOrNull(cat)

    // 分层返回：找片窗开→关窗；未滚到顶→回顶；到顶且无弹窗→回设置页（子页永不直接退 app）
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 } }
    BackHandler {
        when {
            showFind -> showFind = false
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

    // 聚合墙（web runAggWall 等价）：陈旧分片秒开 → 逐源拉新即增量上墙 → 预取毕才起测
    LaunchedEffect(nonce, cat, poolCache) {
        reloadPool()
        val my = wallSeq + 1
        wallSeq = my
        tags.clear()
        val pool = zonePoolNow()
        val c = zoneCatOf()
        val ttl = aggTtl()
        wall = emptyList(); aggPage = 1; aggMore = false; aggLoadingMore = false
        if (pool.isEmpty()) { loading = false; return@LaunchedEffect }
        loading = true
        val drawn = HashSet<String>()
        var firstDrawn = false
        fun mergeFrom(): Boolean {
            val merged = Agg.mergeShards(pool, c)
            if (merged.isEmpty()) return false
            if (!firstDrawn) { firstDrawn = true; wall = Agg.yearDesc(merged) }
            else {
                val fresh = merged.filter { drawn.add(Agg.nameKey(it)) }
                if (fresh.isNotEmpty()) wall = wall + fresh
            }
            merged.forEach { drawn.add(Agg.nameKey(it)) }
            VocabPools.get(context).addWords(merged.map { it.name }, true) // 专区墙片名入专区词库
            aggMore = true
            return true
        }
        mergeFrom()
        val need = pool.take(Agg.POOL_SIZE).filterNot { Agg.fresh(it, c, ttl) }
        coroutineScope {
            need.forEach { s ->
                launch {
                    val got = runCatching { Agg.fetchSource(repo, s, c, 1) }.getOrDefault(emptyList())
                    Agg.putShard(s, c, got)
                    if (my == wallSeq) mergeFrom()
                }
            }
        }
        if (my != wallSeq) return@LaunchedEffect
        loading = false
        if (wall.isNotEmpty()) {
            fillTagsFromCache() // 7 天内已测条目秒显角标；采样测速整体后置（用户 2026-11-04：海报墙优先不抢带宽）
            // 二波补拉（7~12 名）：不阻塞交互，落分片后追加墙尾，再起预取；预取毕才起测
            val back = pool.drop(Agg.POOL_SIZE).filterNot { Agg.fresh(it, c, ttl) }
            launch {
                coroutineScope {
                    back.forEach { s ->
                        launch {
                            val got = runCatching { Agg.fetchSource(repo, s, c, 1) }.getOrDefault(emptyList())
                            Agg.putShard(s, c, got)
                            if (my == wallSeq) mergeFrom()
                        }
                    }
                }
                if (my == wallSeq) {
                    Agg.prefetch(repo, pool, aggTtl()) { my == wallSeq }
                    if (my == wallSeq) { fillTagsFromCache(); speedTest() }
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
        val c = zoneCatOf()
        val pool = zonePoolNow()
        aggLoadingMore = true
        scope.launch {
            coroutineScope {
                pool.forEach { s ->
                    launch {
                        val more = runCatching { Agg.fetchSource(repo, s, c, pg) }.getOrDefault(emptyList())
                        Agg.appendShard(s, c, more)
                    }
                }
            }
            aggLoadingMore = false
            if (my != wallSeq) return@launch
            aggPage = pg
            val all = Agg.mergeShards(pool, c)
            val onWall = wall.mapTo(HashSet()) { Agg.nameKey(it) }
            val added = all.filter { onWall.add(Agg.nameKey(it)) }
            aggMore = added.isNotEmpty()
            if (added.isNotEmpty()) {
                wall = wall + added
                VocabPools.get(context).addWords(added.map { it.name }, true)
                speedTest()
                if (refocus) {
                    delay(80) // 追加重绘后回焦哨兵，支持连按 OK 无限下刷
                    runCatching { sentinelFr.requestFocus() }
                }
            }
        }
    }

    // S4 窗口渲染：本墙条目先只挂 aggWin 部，哨兵「展开更多」本地放量零请求，展完才联网续轮
    val expandable = wall.size > aggWin
    val view = if (expandable) wall.take(aggWin) else wall
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
                item { FuncCard(icon = null, name = "刷新", onClick = { refreshZone() }) }
                item { FuncCard(icon = null, name = "找片", onClick = { showFind = true }) }
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
        CompactChips {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = cat == -1, onClick = { cat = -1 }, label = { Text("最新") })
                }
                itemsIndexed(Agg.AGG_CATS) { i, c ->
                    FilterChip(selected = cat == i, onClick = { cat = i }, label = { Text(c.key) })
                }
            }
        }
        // 提示行整体撤销（对齐点播 2026-11-03 定稿：加载态由墙尾哨兵呈现，空间全让海报墙）；错误兜底保留
        if (!loading && wall.isEmpty()) {
            Text("专区源未返回数据：可点「测速」逐条验证起播，或稍后返回重进", color = MaterialTheme.colorScheme.error)
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(view, key = { "${it.sourceUrl}|${it.vodId}" }) { it ->
                ZonePoster(item = it, tag = tags[zspKey(it)], onClick = { onPick(it) })
            }
            if (view.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    WallSentinel(
                        loading = aggLoadingMore,
                        canMore = expandable || aggMore,
                        label = if (expandable) "⌄ 展开更多（本墙还有 ${wall.size - aggWin} 部）" else null,
                        fr = sentinelFr,
                        onFocus = { sentinelFocused = it },
                        onMore = { aggMoreOrExpand() },
                    )
                }
            }
        }
    }

    // 🔎 找片：专区池全源搜索（web openFindBox('','',zone=true)：空词不自动搜，手输/选词后点钮触发）
    if (showFind) FindSourceDialog(
        store = store,
        title = "",
        zone = true,
        onDismiss = { showFind = false },
        onPick = { showFind = false; onPick(it) },
    )
}

@Composable
private fun ZonePoster(item: VodSummary, tag: PerfCore.SpeedTag?, onClick: () -> Unit) {
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
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(item.year, item.area).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}
