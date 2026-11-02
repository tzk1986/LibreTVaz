package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 固定四大类聚合标签（web AGG_CATS）：按源分类名关键词匹配顶层分类 */
private val AGG_CATS = listOf(
    "电影" to listOf("电影", "影片"),
    "电视剧" to listOf("电视剧", "连续剧", "剧集", "网剧"),
    "综艺" to listOf("综艺", "节目"),
    "动漫" to listOf("动漫", "动画", "番"),
)

/** 墙内条目起播速度缓存（web adsp/zsp 语义，7 天）；进程内存，B11 落盘 */
private val ZSP = MemCache<Long>(600)
private const val ZSP_TTL = 7 * 24 * 3600_000L

/** 专区条目详情缓存（测速取首线路首集用，6h） */
private val ZDETAIL = MemCache<VideoDetail>(300)
private const val ZDETAIL_TTL = 6 * 3600_000L

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
    var cat by remember { mutableStateOf(-1) } // -1=最新；0..3=AGG_CATS
    var wall by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) }
    var speedSeq by remember { mutableIntStateOf(0) }
    val tags = remember { mutableStateMapOf<String, PerfCore.SpeedTag>() } // 「源|id」→角标
    var poolCache by remember { mutableStateOf(store.vodSources().filter { it.isAdult }) }

    BackHandler { onBack() }

    fun zspKey(it: VodSummary) = "zsp:${PerfCore.norm(it.sourceUrl)}|${it.vodId}"

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
                                val d = ZDETAIL.get(k, ZDETAIL_TTL) ?: repo.detail(src, it.vodId)
                                d?.let { dd -> ZDETAIL.set(k, dd) }
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

    // 聚合墙：健康 ms 前 6 池（死源沉底/未测中性 4000），逐源并发拉取增量上墙
    LaunchedEffect(cat, poolCache) {
        reloadPool()
        val my = wallSeq + 1; wallSeq = my
        tags.clear()
        val pool = poolCache
            .sortedBy { s -> health.of(s.url)?.let { h -> if (h.fails >= HealthStore.DEAD_FAILS) Long.MAX_VALUE else h.ms ?: 4000L } ?: 4000L }
            .take(6)
        wall = emptyList()
        if (pool.isEmpty()) { loading = false; return@LaunchedEffect }
        loading = true
        val catKw = AGG_CATS.getOrNull(cat)?.second
        val drawn = HashSet<String>()
        var firstDrawn = false
        coroutineScope {
            pool.forEach { s ->
                launch {
                    val items = runCatching {
                        if (catKw == null) repo.home(s, 1)
                        else {
                            val t = repo.types(s).firstOrNull { x -> catKw.any { k -> x.name.contains(k) } }
                            if (t == null) emptyList() else repo.listByType(s, t.id, 1)
                        }
                    }.getOrDefault(emptyList())
                    if (my != wallSeq || items.isEmpty()) return@launch
                    val fresh = items.filter { drawn.add(CmsParser.normalizeTitle(it.name)) }
                    if (fresh.isEmpty()) return@launch
                    VocabPools.get(context).addWords(fresh.map { it.name }, true) // 专区墙片名入专区词库
                    wall = if (!firstDrawn) {
                        firstDrawn = true
                        (wall + fresh).sortedByDescending { it.year?.toIntOrNull() ?: 0 } // 首绘年降序一次，后续只追加队尾
                    } else wall + fresh
                }
            }
        }
        if (my == wallSeq) {
            loading = false
            speedTest() // 墙拉起后起测（已测条目走 7 天缓存秒回）
        }
    }

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
            itemsIndexed(AGG_CATS) { i, c ->
                FilterChip(selected = cat == i, onClick = { cat = i }, label = { Text(c.first) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (cat < 0) "最新" else AGG_CATS[cat].first, style = MaterialTheme.typography.titleMedium)
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (!loading && wall.isEmpty()) {
            Text("专区源未返回数据：可点「⚡ 测速」逐条验证起播，或稍后返回重进", color = MaterialTheme.colorScheme.error)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(wall, key = { "${it.sourceUrl}|${it.vodId}" }) { it ->
                ZonePoster(item = it, tag = tags[zspKey(it)], onClick = { onPick(it) })
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
