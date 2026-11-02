package com.tzk1986.libretvaz.ui

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

/** 墙内条目起播速度缓存（web adsp/zsp 语义，7 天）；B11 双层：内存+磁盘 */
private val ZSP = DiskBacked(MemCache<Long>(600), "zsp:", CmsCodecs::encLong, CmsCodecs::decLong)
private const val ZSP_TTL = 7 * 24 * 3600_000L

/** 专区条目详情缓存（测速取首线路首集用，6h）；键与角标分离（detail: 命名空间，TTL 不同不得混用） */
private val ZDETAIL = DiskBacked(MemCache<VideoDetail>(300), "detail:", CmsCodecs::encDetail, CmsCodecs::decDetail)
private const val ZDETAIL_TTL = 6 * 3600_000L

/** 设置页「清理缓存」钩子：专区角标测速+条目详情（内存+磁盘双清，web cache_purge 的 zsp 部分） */
internal fun clearZoneCaches() { ZSP.clear(); ZDETAIL.clear() }

/** 退出清理钩子：起播速度角标时效性差，整池作废（内存+磁盘） */
internal fun clearZoneSpeedCache() { ZSP.clear() }

/**
 * 资源口（专区池聚合墙，web renderZone 等价）：预置/自导入专区源按健康 ms 取前 6 聚合，
 * 一源返回即增量上墙（首绘年降序一次，后续未见片名按到达序追加队尾，不动已上屏条目）；
 * 顶栏[搜索/测速/历史/收藏]接各页；条目起播速度角标并发 3、前 60、7 天缓存，失败不判死不移除。
 * 公开克隆无站单=空态「恢复预置」不报错。
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
    var speedSeq by remember { mutableIntStateOf(0) }
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

    BackHandler { onBack() }

    fun zspKey(it: VodSummary) = "zsp:${PerfCore.norm(it.sourceUrl)}|${it.vodId}"
    fun zDetailKey(it: VodSummary) = "detail:${PerfCore.norm(it.sourceUrl)}|${it.vodId}"

    /** 条目起播速度：详情→首线路首集 Range 采样；并发 3、前 60、7 天缓存秒回 */
    fun speedTest() {
        if (!settings.lineSpeed) return
        val s = speedSeq + 1; speedSeq = s
        scope.launch {
            val targets = wall.take(60)
            val sem = Semaphore(3)
            coroutineScope {
                targets.forEach { it ->
                    launch(Dispatchers.IO) {
                        if (s != speedSeq) return@launch
                        val k = zspKey(it)
                        val kb = ZSP.get(k, ZSP_TTL) ?: runCatching {
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

    // 聚合墙（web runAggWall 等价）：陈旧分片秒开 → 逐源拉新即增量上墙 → 起测 → 后台预取其余分类
    LaunchedEffect(cat, poolCache) {
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
        val need = pool.filterNot { Agg.fresh(it, c, ttl) }
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
            speedTest() // 墙拉起后起测（已测条目走 7 天缓存秒回）
            Agg.prefetch(repo, pool, aggTtl()) { my == wallSeq }
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

    AutoLoadMore(gridState, wall.size, aggLoadingMore, aggMore) { aggLoadMore() }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onBack) { Text("← 返回") }
            Text("资源口", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSearch) { Text("🔍 搜索") }
            OutlinedButton(onClick = { speedTest() }) { Text("⚡ 测速") }
            OutlinedButton(onClick = onHist) { Text("🕛 历史") }
            OutlinedButton(onClick = onKeep) { Text("⭐ 收藏") }
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = cat == -1, onClick = { cat = -1 }, label = { Text("最新") })
            }
            itemsIndexed(Agg.AGG_CATS) { i, c ->
                FilterChip(selected = cat == i, onClick = { cat = i }, label = { Text(c.key) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (cat < 0) "最新" else Agg.AGG_CATS[cat].key, style = MaterialTheme.typography.titleMedium)
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (!loading && wall.isEmpty()) {
            Text("专区源未返回数据：可点「⚡ 测速」逐条验证起播，或稍后返回重进", color = MaterialTheme.colorScheme.error)
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(wall, key = { "${it.sourceUrl}|${it.vodId}" }) { it ->
                ZonePoster(item = it, tag = tags[zspKey(it)], onClick = { onPick(it) })
            }
            if (wall.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    WallSentinel(aggLoadingMore, aggMore, sentinelFr, { sentinelFocused = it }) { aggLoadMore() }
                }
            }
        }
    }
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
