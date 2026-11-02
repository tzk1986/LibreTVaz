package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tzk1986.libretvaz.data.Agg
import com.tzk1986.libretvaz.data.AggCat
import com.tzk1986.libretvaz.data.CacheTiers
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HealthStore
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VocabPools
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.VodType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FongMi leanback 同款根页面：
 * 顶栏站名点击弹源切换窗（SiteDialog）；功能卡片行（点播/直播/搜索/源管理/服务器模式）对应各页面；
 * 首页=源无参请求的「更新推荐」海报墙（滚动到底自动翻页）；点「点播」展开分类 chips 进入分类浏览。
 */
@Composable
fun BrowseScreen(
    store: SourceStore,
    onSearch: () -> Unit,
    onLive: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onKeep: () -> Unit,
    onHist: () -> Unit,
    onProbe: () -> Unit,
    onZone: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { CmsRepository() }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val health = remember { HealthStore(context) }
    var sources by remember { mutableStateOf(store.vodPool()) }
    var sourceIdx by remember { mutableStateOf(settings.siteIdx) }
    var types by remember { mutableStateOf<List<VodType>>(emptyList()) }
    var type by remember { mutableStateOf<VodType?>(null) }
    var vodMode by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var targetPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 地区筛选墙（web VOD_FILTERS）：分类命中预设时按 classifyVod 客户端过滤，null=全部
    var preset by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // ===== B10 聚合墙（web Home.aggMode/runAggWall/aggLoadMore 等价）：源弹窗「🌐 聚合」进入，默认仍单源 =====
    var aggMode by remember { mutableStateOf(settings.aggMode) }
    var aggCat by remember { mutableIntStateOf(-1) } // -1=最新；0..3=固定四大类
    var aggPreset by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // 地区预设（标签, 地区集）
    var aggPage by remember { mutableIntStateOf(1) }
    var aggMore by remember { mutableStateOf(false) }
    var aggLoadingMore by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) } // 墙轮次 token：切类/换源/离页后作废旧轮结果
    var pendingRefocus by remember { mutableStateOf(false) } // 哨兵触发的续页画完回焦，遥控可连按 OK
    var sentinelFocused by remember { mutableStateOf(false) }
    val sentinelFr = remember { FocusRequester() }

    fun aggTtl() = CacheTiers.TIERS[settings.cacheTier].agg
    fun aggCatOf(): AggCat? = Agg.AGG_CATS.getOrNull(aggCat)?.let { c ->
        val labs = aggPreset?.second ?: emptyList()
        if (labs.isEmpty()) c else c.copy(rlabs = labs)
    }

    // 从源管理返回时刷新源列表
    LaunchedEffect(Unit) { sources = store.vodPool() }
    if (sourceIdx >= sources.size) sourceIdx = 0
    val source: VodSource? = sources.getOrNull(sourceIdx)

    // 模式切换先清墙与页码，避免两模式残留数据互踩
    LaunchedEffect(aggMode) { items = emptyList(); targetPage = 1 }

    LaunchedEffect(source) {
        type = null
        preset = null
        targetPage = 1
        types = if (aggMode) emptyList() else source?.let { repo.types(it) } ?: emptyList()
    }

    LaunchedEffect(aggMode, source, type, targetPage) {
        if (aggMode) return@LaunchedEffect // 聚合模式由下方墙协程接管
        val s = source ?: return@LaunchedEffect
        val t = type
        loading = true
        error = null
        val page = if (t == null) repo.home(s, targetPage) else repo.listByType(s, t.id, targetPage)
        if (page.isNotEmpty()) VocabPools.get(context).addWords(page.map { it.name }, false) // 进墙片名入点播池词库
        if (targetPage == 1) {
            items = page
            canLoadMore = page.isNotEmpty()
        } else {
            // S4：追加轮 dedupById+dedupByName、以增量判底，防重复卡与空转
            val ids = items.mapTo(HashSet()) { "${it.sourceUrl}|${it.vodId}" }
            val ns = items.mapTo(HashSet()) { Agg.nameKey(it) }
            val added = page.filter { val k = Agg.nameKey(it); k.isNotEmpty() && ns.add(k) && ids.add("${it.sourceUrl}|${it.vodId}") }
            items = items + added
            canLoadMore = added.isNotEmpty()
        }
        val pr = preset
        if (pr != null && pr.second.isNotEmpty() && targetPage >= 10) canLoadMore = false // R8 客户端地区过滤 10 页护栏
        if (page.isEmpty() && targetPage == 1) {
            error = if (type == null) "源未返回最近更新数据，可点「点播」按分类浏览" else "该分类暂无数据，源可能不支持分类列表"
        }
        loading = false
        if (pendingRefocus) {
            pendingRefocus = false
            if (canLoadMore) {
                delay(80) // 等追加重绘完成，回焦落在新哨兵节点上，遥控可连按 OK
                runCatching { sentinelFr.requestFocus() }
            }
        }
    }

    // 聚合墙（web runAggWall 等价）：陈旧分片秒开 → 逐源拉新即增量上墙 → 后台预取其余分类
    LaunchedEffect(aggMode, aggCat, aggPreset, sources) {
        if (!aggMode) return@LaunchedEffect
        val my = wallSeq + 1
        wallSeq = my
        val pool = Agg.poolOf(sources) { s -> Agg.healthMs(health, s) }
        val cat = aggCatOf()
        val ttl = aggTtl()
        items = emptyList(); aggPage = 1; aggMore = false; aggLoadingMore = false
        error = null
        loading = pool.isNotEmpty()
        if (pool.isEmpty()) { error = "聚合池为空，请先在「源管理」导入点播源"; loading = false; return@LaunchedEffect }
        // 队列化渲染：首绘整体年降序一次，此后每轮只把没上墙的片名追加队尾（已显示条目不动）
        val drawn = HashSet<String>()
        var firstDrawn = false
        var mergedOnce = false
        fun mergeFrom(): Boolean {
            val merged = Agg.mergeShards(pool, cat)
            if (merged.isEmpty()) return false
            if (!firstDrawn) { firstDrawn = true; items = Agg.yearDesc(merged) }
            else {
                val fresh = merged.filter { drawn.add(Agg.nameKey(it)) }
                if (fresh.isNotEmpty()) items = items + fresh
            }
            merged.forEach { drawn.add(Agg.nameKey(it)) }
            VocabPools.get(context).addWords(merged.map { it.name }, false) // 新片名进点播池词库
            aggMore = true
            mergedOnce = true
            return true
        }
        mergeFrom()
        val need = pool.filterNot { Agg.fresh(it, cat, ttl) }.toMutableList()
        if (cat != null && cat.rlabs.isNotEmpty() && need.size > 1) { // 地区预设：分类树收录该区的源排前
            val caps = HashMap<String, Boolean>()
            coroutineScope { need.forEach { s -> launch { caps[s.url] = Agg.hasRegionKid(Agg.typesOf(repo, s), cat) } } }
            if (my != wallSeq) return@LaunchedEffect
            need.sortByDescending { if (caps[it.url] == true) 1 else 0 }
        }
        coroutineScope {
            need.forEach { s ->
                launch {
                    val got = runCatching { Agg.fetchSource(repo, s, cat, 1) }.getOrDefault(emptyList())
                    Agg.putShard(s, cat, got)
                    if (my == wallSeq) mergeFrom() // 一源返回就重画，不等最慢源
                }
            }
        }
        if (my != wallSeq) return@LaunchedEffect
        loading = false
        if (!mergedOnce) {
            error = "聚合无数据：${pool.size} 个源均未返回" +
                (if (cat == null) "最新" else "「${cat.key}${aggPreset?.first?.let { "·$it" } ?: ""}」") +
                "（源无此地区分类或拉取超时），可选「全部」或换源重试"
        } else {
            Agg.prefetch(repo, pool, aggTtl()) { my == wallSeq } // 切类秒开的后台铺垫（只进分片）
        }
    }

    // S4 无限下拉：各源同分类 pg+1 追加分片→整墙重并；本轮整墙有新增就能一直按（无轮次上限）
    fun aggLoadMore() {
        if (!aggMode || !aggMore || aggLoadingMore) return
        if (aggPage >= Agg.FUSE_ROUND) { aggMore = false; return } // 极端保险丝，正常以增量判底
        val refocus = sentinelFocused
        val my = wallSeq
        val pg = aggPage + 1
        val cat = aggCatOf()
        val pool = Agg.poolOf(sources) { s -> Agg.healthMs(health, s) }
        aggLoadingMore = true
        scope.launch {
            coroutineScope {
                pool.forEach { s ->
                    launch {
                        val more = runCatching { Agg.fetchSource(repo, s, cat, pg) }.getOrDefault(emptyList())
                        Agg.appendShard(s, cat, more) // 分片 dedupById + 800 封顶
                    }
                }
            }
            aggLoadingMore = false
            if (my != wallSeq) return@launch
            aggPage = pg
            val all = Agg.mergeShards(pool, cat)
            val onWall = items.mapTo(HashSet()) { Agg.nameKey(it) }
            val added = all.filter { onWall.add(Agg.nameKey(it)) } // 本轮见过的不再上墙
            aggMore = added.isNotEmpty() // 全重复/空=到底
            if (added.isNotEmpty()) {
                items = items + added
                VocabPools.get(context).addWords(added.map { it.name }, false)
                if (refocus) {
                    delay(80) // 追加重绘后回焦哨兵，支持连按 OK 无限下刷
                    runCatching { sentinelFr.requestFocus() }
                }
            }
        }
    }

    val gridState = rememberLazyGridState()
    val regionSel = preset?.second ?: emptyList()
    val shown = if (regionSel.isEmpty()) items
        else items.filter { regionSel.contains(PerfCore.classifyVod(it.typeName, it.area)) }
    val aggLabs = aggPreset?.second ?: emptyList()
    val aggShown = if (aggLabs.isEmpty()) items
        else items.filter { aggLabs.contains(PerfCore.classifyVod(it.typeName, it.area)) }
    AutoLoadMore(
        gridState,
        if (aggMode) aggShown.size else shown.size,
        if (aggMode) aggLoadingMore else loading,
        if (aggMode) aggMore else canLoadMore,
    ) { if (aggMode) aggLoadMore() else targetPage++ }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "LibreTVaz",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            AssistChip(
                onClick = { showSourcePicker = true },
                label = {
                    Text(
                        if (aggMode) "当前源：🌐 聚合"
                        else source?.name?.let { "当前源：$it" } ?: "未配置点播源",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }

        if (sources.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("尚未配置点播源，导入后即可在此浏览海报墙", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSources) { Text("进入源管理") }
            }
            return@Column
        }

        // 功能行：对应各页面按钮（源管理/服务器模式即原顶栏按钮的入口）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(orderedCards(settings)) { _, card ->
                FuncCard(
                    title = card.title,
                    caption = card.caption,
                    selected = card.id == "vod" && vodMode,
                    onClick = {
                        when (card.id) {
                            "vod" -> vodMode = true
                            "live" -> onLive()
                            "search" -> onSearch()
                            "keep" -> onKeep()
                            "hist" -> onHist()
                            "probe" -> onProbe()
                            "zone" -> onZone()
                            "sources" -> onSources()
                            "settings" -> onSettings()
                        }
                    },
                )
            }
        }

        if (aggMode) {
            // 聚合墙分类标签（web AGG_CATS：最新+固定四大类，不依赖单源分类树）
            val aggIdx = aggCat
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = aggCat == -1, onClick = { aggCat = -1; aggPreset = null }, label = { Text("最新") })
                }
                items(Agg.AGG_CATS) { c ->
                    FilterChip(
                        selected = aggIdx >= 0 && Agg.AGG_CATS.getOrNull(aggIdx)?.key == c.key,
                        onClick = { aggCat = Agg.AGG_CATS.indexOf(c); aggPreset = null },
                        label = { Text(c.key) },
                    )
                }
            }
            // 地区预设（web R8(agg)）：rlabs 定向拉取 + classifyVod 客户端过滤
            val aggPresets = Agg.AGG_CATS.getOrNull(aggCat)?.let { PerfCore.VOD_FILTERS[it.key] }
            if (aggPresets != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(aggPresets) { (label, regions) ->
                        FilterChip(
                            selected = (aggPreset?.first ?: "全部") == label,
                            onClick = { aggPreset = if (regions.isEmpty()) null else label to regions },
                            label = { Text(label) },
                        )
                    }
                }
            }
        } else {
            if (vodMode && types.isNotEmpty()) {
                val typeList = types
                val currentType = type
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = currentType == null,
                            onClick = { type = null; preset = null; targetPage = 1 },
                            label = { Text("更新推荐") },
                        )
                    }
                    items(typeList) { t ->
                        FilterChip(
                            selected = t.id == currentType?.id,
                            onClick = { type = t; preset = null; targetPage = 1 },
                            label = { Text(t.name) },
                        )
                    }
                }
            }

            // 地区筛选 chips：仅当前分类命中预设（电影/电视剧/综艺/动漫）时出现，切换回第 1 页重拉
            val typeFilters = type?.let { PerfCore.VOD_FILTERS[it.name] }
            if (vodMode && typeFilters != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(typeFilters) { (label, regions) ->
                        FilterChip(
                            selected = (preset?.first ?: "全部") == label,
                            onClick = { preset = label to regions; targetPage = 1 },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    aggMode -> (if (aggCat < 0) "最新" else Agg.AGG_CATS.getOrNull(aggCat)?.key ?: "最新") +
                        (aggPreset?.first?.let { "·$it" } ?: "")
                    else -> type?.name ?: "更新推荐"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (error != null && !loading) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (aggMode) {
                itemsIndexed(aggShown, key = { _, it -> "${it.sourceUrl}|${it.vodId}" }) { _, item ->
                    PosterCard(item = item, onClick = { onPick(item) }, siteLabel = item.sourceName)
                }
                if (aggShown.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WallSentinel(aggLoadingMore, aggMore, sentinelFr, { sentinelFocused = it }) { aggLoadMore() }
                    }
                }
            } else {
                itemsIndexed(shown) { _, item ->
                    PosterCard(item = item, onClick = { onPick(item) })
                }
                if (shown.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WallSentinel(loading, canLoadMore, sentinelFr, { sentinelFocused = it }) {
                            pendingRefocus = sentinelFocused
                            targetPage++
                        }
                    }
                }
            }
        }
    }

    if (showSourcePicker) {
        val pickerSources = sources
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            confirmButton = {
                TextButton(onClick = { showSourcePicker = false }) { Text("关闭") }
            },
            title = { Text("切换点播源") },
            text = {
                LazyColumn {
                    item {
                        PickerRow(
                            name = "🌐 聚合",
                            sub = "全源合并·健康序前 6",
                            selected = aggMode,
                            onClick = {
                                aggMode = true
                                settings.aggMode = true
                                showSourcePicker = false
                            },
                        )
                    }
                    itemsIndexed(pickerSources) { i, s ->
                        val current = s
                        val selected = !aggMode && i == sourceIdx
                        PickerRow(
                            name = current.name,
                            sub = null,
                            selected = selected,
                            onClick = {
                                aggMode = false
                                settings.aggMode = false
                                sourceIdx = i
                                settings.siteIdx = i
                                showSourcePicker = false
                            },
                        )
                    }
                }
            },
        )
    }
}

/** 聚合墙尾部哨兵（web moreBtn 等价）：可聚焦按钮=遥控 OK 续轮；加载中/到底退化为文本 */
@Composable
internal fun WallSentinel(
    loading: Boolean,
    canMore: Boolean,
    fr: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    when {
        loading -> Text("加载中…", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        canMore -> Button(
            onClick = onMore,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(fr)
                .onFocusEvent { onFocus(it.isFocused) },
        ) { Text("⌄ 加载更多（确认键继续加载）") }
        else -> Text("已到底（各源无新内容）", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun PickerRow(name: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        }
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
}

/** 焦点/滚动到最后一项时自动翻页（FongMi onLoadMore 行为） */
@Composable
internal fun AutoLoadMore(
    gridState: LazyGridState,
    itemCount: Int,
    loading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount, loading, canLoadMore) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to (itemCount > 0 && canLoadMore && !loading && last >= itemCount - 1)
        }
            .collect { (_, shouldLoad) -> if (shouldLoad) onLoadMore() }
    }
}

internal data class FuncCardSpec(val id: String, val title: String, val caption: String)

internal fun functionCards() = listOf(
    FuncCardSpec("vod", "点播", "分类浏览"),
    FuncCardSpec("live", "直播", "看电视"),
    FuncCardSpec("search", "搜索", "多源聚合"),
    FuncCardSpec("keep", "收藏", "追剧收藏"),
    FuncCardSpec("hist", "历史", "播放记录"),
    FuncCardSpec("probe", "测速", "源连接探活"),
    FuncCardSpec("zone", "资源口", "专区聚合墙"),
    FuncCardSpec("sources", "源管理", "导入订阅/源设置"),
    FuncCardSpec("settings", "设置", "播放/服务器/代理"),
)

/** 功能行 = tabsJson 存档序+显隐过滤（web getTabs 等价：点播恒首位、未知键丢弃、缺失项按默认序补显示） */
private fun orderedCards(settings: SettingsStore): List<FuncCardSpec> {
    val all = functionCards()
    if (settings.tabsJson.isBlank()) return all
    val saved = runCatching {
        val arr = org.json.JSONArray(settings.tabsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("nav") to (o.optInt("show", 1) != 0)
        }
    }.getOrDefault(emptyList())
    if (saved.isEmpty()) return all
    val byId = all.associateBy { it.id }
    val out = ArrayList<FuncCardSpec>()
    byId["vod"]?.let { out.add(it) }
    for ((nav, show) in saved) {
        if (nav == "vod" || !show) continue
        byId[nav]?.takeIf { it !in out }?.let { out.add(it) }
    }
    for (spec in all) {
        if (spec.id != "vod" && saved.none { it.first == spec.id }) out.add(spec)
    }
    return out
}

@Composable
private fun FuncCard(title: String, caption: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier,
    ) {
        Column(
            modifier = Modifier.width(112.dp).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PosterCard(item: VodSummary, onClick: () -> Unit, siteLabel: String? = null) {
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
            item.remarks?.let { r ->
                Text(
                    text = r,
                    color = MaterialTheme.colorScheme.onSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            siteLabel?.let { t ->
                Text(
                    text = t,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdultTag(adultOf(item))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = listOfNotNull(item.year, item.area).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}
