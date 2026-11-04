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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tzk1986.libretvaz.data.perf.Region
import com.tzk1986.libretvaz.data.Agg
import com.tzk1986.libretvaz.data.AggCat
import com.tzk1986.libretvaz.data.CacheTiers
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HealthStore
import com.tzk1986.libretvaz.data.KeepStore
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VocabPools
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.VodType
import com.tzk1986.libretvaz.data.VodFilters
import com.tzk1986.libretvaz.data.CmsParser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FongMi leanback 同款根页面（P9 点播即首页）：
 * 顶栏站名点击弹源切换窗（SiteDialog）；功能行=「🔄 刷新」固定栏首+tabs 存档序导航（无「点播」项，分类 chips 恒在）；
 * 首页=源无参请求的「更新推荐」海报墙（滚动到底自动翻页）；聚合墙 240 窗口渲染：哨兵先本地展开零请求，展完再联网续轮。
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
    onPick: (VodSummary) -> Unit,
    onAggSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { CmsRepository() }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val health = remember { HealthStore(context) }
    // 顶栏状态（用户 2026-11-03：隐藏系统状态栏后，网络+时钟并排显示在换源钮同一行；资源口顶栏同款复用）
    val conn = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    var netLabel by remember { mutableStateOf("…") }
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val caps = conn?.activeNetwork?.let { conn.getNetworkCapabilities(it) }
            netLabel = when {
                caps == null -> "离线"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> "已连接"
            }
            clockText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            delay(20_000)
        }
    }

    // 首页双击返回退出（用户 2026-11-03）：首次提示，2s 内再按才真正退出
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var sources by remember { mutableStateOf(store.vodPool()) }
    var sourceIdx by remember { mutableStateOf(settings.siteIdx) }
    var types by remember { mutableStateOf<List<VodType>>(emptyList()) }
    var type by remember { mutableStateOf<VodType?>(null) }
    var nonce by remember { mutableIntStateOf(0) } // 🔄刷新钮轮次 token：清缓存后重触发分类树+首页拉取
    var showSourcePicker by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var targetPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 地区筛选墙（web Region.PRESETS）：分类命中预设时按 Region.of 客户端过滤，null=全部
    var preset by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // web Vod.pSrv：预设命中分类树地区子分类→服务端按这些 id 合并拉取（三级 (a)）；null=客户端 (b)
    var pSrv by remember { mutableStateOf<List<VodType>?>(null) }
    // 服务端二级筛选（web Vod.sub/area/year/lang + buildFilters）：子类覆盖 t，扩展值拼 &area=&year=&lang=
    var subId by remember { mutableStateOf<String?>(null) }
    var areaSel by remember { mutableStateOf<String?>(null) }
    var yearSel by remember { mutableStateOf<String?>(null) }
    var langSel by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf<VodFilters?>(null) } // web Vod.filters：pg=1 响应后重建
    // ===== B10 聚合墙（web Home.aggMode/runAggWall/aggLoadMore 等价）：首启默认聚合，源弹窗可随时切回单源 =====
    var aggMode by remember { mutableStateOf(settings.aggMode) }
    var aggCat by remember { mutableIntStateOf(-1) } // -1=最新；0..3=固定四大类
    var aggPreset by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // 地区预设（标签, 地区集）
    var showFilters by remember { mutableStateOf(false) } // web Vod.showFilters：再点当前分类=展开/收起筛选行
    var aggPage by remember { mutableIntStateOf(1) }
    var aggMore by remember { mutableStateOf(false) }
    var aggLoadingMore by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) } // 墙轮次 token：切类/换源/离页后作废旧轮结果
    var aggWin by remember { mutableIntStateOf(240) } // S4 窗口渲染：聚合墙每次只挂 240 部，哨兵本地展开零请求（web Vod.win 等价）
    var pendingRefocus by remember { mutableStateOf(false) } // 哨兵触发的续页画完回焦，遥控可连按 OK
    var sentinelFocused by remember { mutableStateOf(false) }
    val sentinelFr = remember { FocusRequester() }

    fun aggTtl() = CacheTiers.TIERS[settings.cacheTier].agg
    // 地区预设＝纯客户端过滤（用户 2026-11-04 定稿）：聚合取数不随预设改道，复用「全部」同一分片，
    // 切换零重拉、后续轮次续拉照常进墙，由 Region.of 逐条裁决——防源漏挂类丢条目
    fun aggCatOf(): AggCat? = Agg.AGG_CATS.getOrNull(aggCat)

    // 从源管理返回时刷新源列表
    LaunchedEffect(Unit) { sources = store.vodPool() }
    if (sourceIdx >= sources.size) sourceIdx = 0
    val source: VodSource? = sources.getOrNull(sourceIdx)

    // 模式切换先清墙与页码，避免两模式残留数据互踩
    LaunchedEffect(aggMode) { items = emptyList(); targetPage = 1; showFilters = false }

    // web resetFilterVals：子类/扩展值/地区预设一并清
    fun resetFilterVals() {
        subId = null; areaSel = null; yearSel = null; langSel = null; preset = null; pSrv = null; filters = null
    }

    // web buildFilters：子类=pid 指向当前分类；扩展值=分类 extend 与 videolist meta 合并（meta 优先）
    fun buildFiltersOf(t: VodType, meta: Map<String, String>?): VodFilters {
        val e = t.extend + (meta ?: emptyMap())
        fun arr(k: String) = (e[k] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return VodFilters(
            subs = types.filter { it.pid == t.id && it.id != t.id },
            area = arr("area"), year = arr("year"), lang = arr("lang"),
        )
    }

    LaunchedEffect(source) {
        type = null
        resetFilterVals()
        targetPage = 1
        showFilters = false
        types = if (aggMode) emptyList() else source?.let { repo.types(it) } ?: emptyList()
    }

    // 刷新钮（单源）：分类树已清 → 重拉；当前分类按 id 重解析到新树（web loadTypes 后 tab 重解析等价），地区预设保留
    LaunchedEffect(nonce) {
        if (nonce == 0 || aggMode) return@LaunchedEffect
        val s = source ?: return@LaunchedEffect
        val keepId = type?.id
        val newTypes = runCatching { repo.types(s) }.getOrDefault(emptyList())
        types = newTypes
        type = keepId?.let { k -> newTypes.find { it.id == k } } ?: type
        targetPage = 1
    }

    LaunchedEffect(nonce, aggMode, source, type, targetPage, subId, areaSel, yearSel, langSel) {
        if (aggMode) return@LaunchedEffect // 聚合模式由下方墙协程接管
        val s = source ?: return@LaunchedEffect
        val t = type
        loading = true
        error = null
        // web loadVod 分支序：①preset&&pSrv→服务端合并 ②tab 有子类且未选子→父类聚子类 ③直查 effT+area/year/lang
        var mergedMode = false
        var mergedAny = false
        val page = if (t == null) {
            filters = null
            repo.home(s, targetPage)
        } else {
            val kids = types.filter { it.pid == t.id && it.id != t.id } // web applyTabMerged
            val pr0 = preset
            val srv0 = pSrv
            val mk = when {
                pr0 != null && srv0 != null -> srv0
                kids.isNotEmpty() && subId == null -> kids
                else -> null
            }
            if (mk != null) {
                // web loadMerged：mrg: SWR 秒显 → 6 路并发逐子类 → dedupById+dedupByName；pg=1 非空回存
                mergedMode = true
                val ck = Agg.mrgKey(
                    s.url, t.id, areaSel ?: "", yearSel ?: "", langSel ?: "",
                    if (pr0 != null && srv0 != null) pr0.first else "",
                )
                val cachedShown = if (targetPage == 1) {
                    val c0 = Agg.mrgPeek(ck)
                    if (c0 != null && c0.isNotEmpty()) { items = Agg.yearDesc(c0); true } else false // 旧数据先上屏秒显
                } else false
                val pages = Agg.fetchMerged(repo, s, mk, areaSel ?: "", yearSel ?: "", langSel ?: "", targetPage)
                val fresh = Agg.dedupByName(Agg.dedupById(pages.flatten()))
                if (targetPage == 1 && fresh.isEmpty() && cachedShown) { loading = false; return@LaunchedEffect } // 拉新为空保留缓存首屏
                mergedAny = pages.any { it.isNotEmpty() }
                if (targetPage == 1) {
                    if (fresh.isNotEmpty()) Agg.mrgPut(ck, fresh)
                    filters = buildFiltersOf(t, null) // web L1621：合并模式 raw=null，仅子类+extend
                }
                fresh
            } else {
                val (list, raw) = repo.listFiltered(
                    s, subId ?: t.id, areaSel ?: "", yearSel ?: "", langSel ?: "", targetPage,
                )
                if (targetPage == 1) filters = buildFiltersOf(t, raw?.let { CmsParser.parseFilterMeta(it) })
                list
            }
        }
        if (page.isNotEmpty()) VocabPools.get(context).addWords(page.map { it.name }, false) // 进墙片名入点播池词库
        if (targetPage == 1) {
            // web loadVod pg===1：sortByYearDesc——首绘年降序最新优先（同年保持源序），增量轮追加队尾不重排
            items = Agg.yearDesc(page)
            canLoadMore = if (mergedMode) mergedAny else page.isNotEmpty() // web L1625：pg=1 任一子类有页即可续
        } else {
            // S4：追加轮 dedupById+dedupByName、以增量判底，防重复卡与空转
            val ids = items.mapTo(HashSet()) { "${it.sourceUrl}|${it.vodId}" }
            val ns = items.mapTo(HashSet()) { Agg.nameKey(it) }
            val added = page.filter { val k = Agg.nameKey(it); k.isNotEmpty() && ns.add(k) && ids.add("${it.sourceUrl}|${it.vodId}") }
            items = items + added
            canLoadMore = added.isNotEmpty()
        }
        val pr = preset
        if (pr != null && pSrv == null && targetPage >= 10) canLoadMore = false // R8 客户端地区过滤 10 页护栏（服务端合并 pSrv 不受限，web atCap）
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

    // 聚合墙（web runAggWall 等价+两波扩池）：陈旧分片秒开 → 首波 top6 逐源增量上墙 → 二波 7~12 后台补拉 → 预取其余分类
    LaunchedEffect(nonce, aggMode, aggCat, aggPreset, sources) {
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
        val need = pool.take(Agg.POOL_SIZE).filterNot { Agg.fresh(it, cat, ttl) }.toMutableList()
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
            // 二波补拉：UI 已解锁，7~12 名并发落分片后按片名去重追加墙尾；完再预取其余分类
            val back = pool.drop(Agg.POOL_SIZE).filterNot { Agg.fresh(it, cat, ttl) }.toMutableList()
            launch {
                coroutineScope {
                    back.forEach { s ->
                        launch {
                            val got = runCatching { Agg.fetchSource(repo, s, cat, 1) }.getOrDefault(emptyList())
                            Agg.putShard(s, cat, got)
                            if (my == wallSeq) mergeFrom()
                        }
                    }
                }
                if (my == wallSeq) Agg.prefetch(repo, pool, aggTtl()) { my == wallSeq }
            }
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
    // R8 三级 (b)：仅客户端过滤模式（preset 且无 pSrv）生效，UNKNOWN 保守剔除并计数（web drawVodGrid）
    val prNow = preset
    val regionSel = if (prNow != null && pSrv == null) prNow.second else emptyList()
    var unknownCnt = 0
    val shown = if (regionSel.isEmpty()) items else run {
        val l = ArrayList<VodSummary>()
        for (it in items) {
            val r = Region.of(it)
            if (r == "UNKNOWN") { unknownCnt++; continue }
            if (regionSel.contains(r)) l.add(it)
        }
        l
    }
    val aggLabs = aggPreset?.second ?: emptyList()
    val aggShown = if (aggLabs.isEmpty()) items
        else items.filter { aggLabs.contains(Region.of(it)) }
    // S4 窗口渲染：墙内条目先只挂 aggWin 部；哨兵「展开更多」本地放量零请求，展完才联网续轮（web moreBtn.onclick 等价）
    val aggExpandable = aggShown.size > aggWin
    val aggView = if (aggExpandable) aggShown.take(aggWin) else aggShown
    fun aggMoreOrExpand() { if (aggExpandable) aggWin += 240 else aggLoadMore() }
    AutoLoadMore(
        gridState,
        if (aggMode) aggView.size else shown.size,
        if (aggMode) aggLoadingMore else loading,
        if (aggMode) aggExpandable || aggMore else canLoadMore,
    ) { if (aggMode) aggMoreOrExpand() else targetPage++ }

    /** 🔄 刷新钮（web vodRefresh 等价）：只清点播相关分片（历史/收藏/测速不动），清后重拉本页 */
    fun refreshPage() {
        if (aggMode) Agg.purgeWallShards() else source?.let { Agg.purgeTypesOf(it.url); Agg.purgeMrgOf(it.url) }
        Toast.makeText(context, "已清本页缓存，重新拉取中…", Toast.LENGTH_SHORT).show()
        nonce++
    }

    // 分层返回（web 顶栏式）：弹窗开→关窗；未滚到顶→回顶；到顶且无弹窗→双击返回退出 app
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 } }
    BackHandler {
        when {
            showSourcePicker -> showSourcePicker = false
            !atTop -> scope.launch { gridState.animateScrollToItem(0) }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt <= 2000) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 紧凑化（用户 2026-11-03 TV/投影要求）：列间距 8→4dp，chip 行最小触控 48→26dp，标题降字号——纵向全让海报墙
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 顶栏（用户 2026-11-03：功能行并入此行、源钮改同款小方块，再省一行空间）：仅首页显示，
        // 一行＝源钮+刷新+导航小方块（横滚）+ 右网络·时钟（状态栏已隐藏，此处自绘替代）；换源回首页切
        val atHomeNow = if (aggMode) aggCat == -1 else type == null
        // web 导航「收藏·N更」徽标：回首页重读更新数（checkKeeps 由收藏页触发写库，此处只展示）
        val keepUpdCount = remember { mutableIntStateOf(0) }
        LaunchedEffect(atHomeNow) {
            if (atHomeNow) keepUpdCount.intValue = KeepStore(context).updatedCount(false)
        }
        if (atHomeNow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FuncCard(
                            icon = if (aggMode) "🌐" else "📺",
                            name = if (aggMode) "聚合" else source?.name ?: "无源",
                            onClick = { showSourcePicker = true },
                        )
                    }
                    // 常用置顶（用户 2026-11-03）：历史→搜索→刷新→其余按导航栏存档序
                    val cards = orderedCards(settings)
                    fun go(id: String) {
                        when (id) {
                            "live" -> onLive()
                            "search" -> onSearch()
                            "keep" -> onKeep()
                            "hist" -> onHist()
                            "probe" -> onProbe()
                            "sources" -> onSources()
                            "settings" -> onSettings()
                        }
                    }
                    val pinned = listOf("hist", "search").mapNotNull { id -> cards.firstOrNull { it.id == id } }
                    itemsIndexed(pinned) { _, card ->
                        FuncCard(icon = null, name = card.title, onClick = { go(card.id) })
                    }
                    item { FuncCard(icon = null, name = "刷新", onClick = { refreshPage() }) }
                    itemsIndexed(cards.filter { it.id != "hist" && it.id != "search" }) { _, card ->
                        FuncCard(
                            icon = null,
                            name = if (card.id == "keep" && keepUpdCount.intValue > 0) "收藏·${keepUpdCount.intValue}更" else card.title,
                            onClick = { go(card.id) },
                        )
                    }
                }
                // 网络/时钟同款纯文字小钮（用户 2026-11-03）
                StatCard(netLabel)
                StatCard(clockText)
            }
        }

        // 点播即首页（web P8）：无源时保留顶栏骨架，内容区仅提示（对齐 web typeRow 文案）
        if (sources.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("无点播源：在「源管理」导入订阅或添加内置源", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Button(onClick = onSources) { Text("源管理") }
            }
            return@Column
        }

        CompactChips {
        if (aggMode) {
            // 聚合墙分类标签（web AGG_CATS：最新+固定四大类，不依赖单源分类树）
            val aggIdx = aggCat
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // web：再点当前「最新」=no-op（!id return），切过去才清预设并收起筛选行
                    FilterChip(selected = aggCat == -1, onClick = {
                        if (aggCat != -1) { aggCat = -1; aggPreset = null; showFilters = false }
                    }, label = { Text("最新") })
                }
                items(Agg.AGG_CATS) { c ->
                    val i = Agg.AGG_CATS.indexOf(c)
                    FilterChip(
                        selected = aggIdx == i,
                        onClick = {
                            if (aggIdx == i) {
                                // web R8(agg)：再点当前分类=展开/收起地区行；无预设的分类不可展开
                                if (Region.PRESETS[c.key]?.isNotEmpty() == true) showFilters = !showFilters
                            } else { aggCat = i; aggPreset = null; showFilters = false }
                        },
                        label = { Text(c.key) },
                    )
                }
            }
            // 地区预设（web R8(agg) drawSubRow）：门控 tab+showFilters，同项再点=取消
            val aggPresets = Agg.AGG_CATS.getOrNull(aggCat)?.let { Region.PRESETS[it.key] }
            if (aggPresets != null && showFilters) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(aggPresets) { (label, regions) ->
                        FilterChip(
                            selected = (aggPreset?.first ?: "全部") == label,
                            onClick = {
                                // 选中即收起筛选行让位海报墙（用户 2026-11-03 TV 大屏要求）；取消/全部保持展开
                                val cancel = regions.isEmpty() || aggPreset?.first == label
                                aggPreset = if (cancel) null else label to regions
                                if (!cancel) showFilters = false
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        } else {
            if (types.isNotEmpty()) {
                val typeList = types
                val currentType = type
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        // web：id=='' 分支恒重拉最新（非 toggle），并收起筛选行
                        FilterChip(
                            selected = currentType == null,
                            onClick = { type = null; resetFilterVals(); showFilters = false; targetPage = 1 },
                            label = { Text("更新推荐") },
                        )
                    }
                    items(typeList) { t ->
                        FilterChip(
                            selected = t.id == currentType?.id,
                            onClick = {
                                if (t.id == currentType?.id) {
                                    showFilters = !showFilters // web：再点当前分类=展开/收起二级筛选
                                } else {
                                    type = t; resetFilterVals(); showFilters = false; targetPage = 1
                                }
                            },
                            label = { Text(t.name) },
                        )
                    }
                }
            }

            // 二级筛选行（web drawSubRow）：地区预设 + 子类/地区(源扩展)/年份/语言组，门控 tab+showFilters
            val curType = type
            if (curType != null && showFilters) {
                val f = filters
                // web presetKeyForTab：沿 pid 上溯根名+本名匹配预设族（「电影片」「电视剧(年)」等别名也能命中）
                val presets = Agg.presetKeyOf(curType, types)?.let { Region.PRESETS[it] }
                val hasGrps = f != null && !f.isEmpty()
                if ((presets == null || presets.isEmpty()) && !hasGrps) {
                    Text(
                        "该分类不支持二级筛选（源未提供子分类或扩展字段）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    val yr = java.time.LocalDate.now().year.toString()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val label = @Composable { s: String ->
                            Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        // ① 地区预设：树里有地区子分类→服务端合并 (a)，否则客户端 Region.of 兜底 (b)；
                        //   置灰=无命中且已载条目无匹配亦无 UNKNOWN（web L1743）；选中即收起筛选行（用户 2026-11-03 TV 大屏要求）
                        if (!presets.isNullOrEmpty()) {
                            item { label("地区") }
                            items(presets) { (pLabel, regions) ->
                                val pOff = regions.isNotEmpty() &&
                                    Agg.presetIdsOf(regions, curType, types) == null &&
                                    items.isNotEmpty() &&
                                    !items.any { val r = Region.of(it); r == "UNKNOWN" || regions.contains(r) }
                                FilterChip(
                                    selected = (preset?.first ?: "全部") == pLabel,
                                    onClick = {
                                        if (pOff) {
                                            Toast.makeText(context, "该源分类未收录此地区内容", Toast.LENGTH_SHORT).show()
                                            return@FilterChip
                                        }
                                        val cancel = regions.isEmpty() || preset?.first == pLabel
                                        preset = if (cancel) null else pLabel to regions
                                        pSrv = (preset)?.let { Agg.presetIdsOf(it.second, curType, types) }
                                        if (!cancel) showFilters = false
                                        targetPage = 1
                                    },
                                    label = {
                                        Text(
                                            pLabel,
                                            color = if (pOff) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) else Color.Unspecified,
                                        )
                                    },
                                )
                            }
                        }
                        // ② 子类组：pid 命中当前分类的子类，点选=服务端 t 覆盖（web Vod.sub）
                        if (f != null && f.subs.isNotEmpty()) {
                            item { label("子类") }
                            item {
                                FilterChip(selected = subId == null, onClick = { subId = null; targetPage = 1 }, label = { Text("全部") })
                            }
                            items(f.subs.take(30)) { sub ->
                                FilterChip(
                                    selected = subId == sub.id,
                                    onClick = { subId = sub.id; showFilters = false; targetPage = 1 },
                                    label = { Text(sub.name) },
                                )
                            }
                        }
                        // ③④⑤ 扩展组（web grp()）：全部+选项≤30，同项再按=取消；年份插本年去重；选中即收起（用户 2026-11-03）
                        fun grpItems(name: String, opts: List<String>, sel: String?, clear: () -> Unit, pick: (String) -> Unit) {
                            item { label(name) }
                            item { FilterChip(selected = sel == null, onClick = { clear(); targetPage = 1 }, label = { Text("全部") }) }
                            items(opts.take(30)) { v ->
                                FilterChip(
                                    selected = sel == v,
                                    onClick = { if (sel == v) clear() else { pick(v); showFilters = false }; targetPage = 1 },
                                    label = { Text(if (v == yr) "本年($yr)" else v) },
                                )
                            }
                        }
                        if (f != null) {
                            if (f.area.isNotEmpty()) grpItems("地区(源扩展)", f.area, areaSel, { areaSel = null }, { areaSel = it })
                            val years = listOf(yr) + f.year.filter { it != yr }
                            if (f.year.isNotEmpty()) grpItems("年份", years, yearSel, { yearSel = null }, { yearSel = it })
                            if (f.lang.isNotEmpty()) grpItems("语言", f.lang, langSel, { langSel = null }, { langSel = it })
                        }
                    }
                }
            }
        }
        }

        // 功能行已并入首页顶栏（用户 2026-11-03：源旁同款小方块，再省一行让位海报墙）

        // 分类/预设提示行整体撤销（用户 2026-11-03：各分类筛选后的提示文字全部去除，空间全让海报墙；
        // 加载态由哨兵行呈现，错误仍由下方 error 行兜底）
        if (error != null && !loading) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (aggMode) {
                // 聚合墙卡片（用户 2026-11-03 要求，偏离 web 点卡直达详情）：只显示海报+名称（数据层
                // mergeShards 已按归一剧名去重），点击=带剧名进搜索页全源聚合，用户再选源播放
                itemsIndexed(aggView, key = { _, it -> "${it.sourceUrl}|${it.vodId}" }) { _, item ->
                    PosterCard(item = item, onClick = { onAggSearch(item.name) })
                }
                if (aggView.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WallSentinel(
                            loading = aggLoadingMore,
                            canMore = aggExpandable || aggMore,
                            label = if (aggExpandable) "⌄ 展开更多（本墙还有 ${aggShown.size - aggWin} 部）" else null,
                            fr = sentinelFr,
                            onFocus = { sentinelFocused = it },
                            onMore = { aggMoreOrExpand() },
                        )
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
                            sub = "全源合并·健康序前 12（首波 6）",
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
    label: String? = null,
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
        ) { Text(label ?: "⌄ 加载更多（确认键继续加载）") }
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

internal data class FuncCardSpec(val id: String, val title: String)

/** 可入栏功能卡（P9 web getTabs 等价：点播即首页不入栏、资源口入口在实验室，均不在此列） */
internal fun functionCards() = listOf(
    FuncCardSpec("live", "直播"),
    FuncCardSpec("search", "搜索"),
    FuncCardSpec("keep", "收藏"),
    FuncCardSpec("hist", "历史"),
    FuncCardSpec("probe", "连接"),
    FuncCardSpec("sources", "源管理"),
    FuncCardSpec("settings", "设置"),
)

/** 功能行 = tabsJson 存档序+显隐过滤（web getTabs 等价：旧档 vod/zone 项一并滤掉、未知键丢弃、缺失项按默认序补显示） */
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
    for ((nav, show) in saved) {
        if (!show) continue
        byId[nav]?.takeIf { it !in out }?.let { out.add(it) }
    }
    for (spec in all) {
        if (saved.none { it.first == spec.id }) out.add(spec)
    }
    return out
}

/** 芯片/顶栏行紧凑化（用户 2026-11-03）：Material3 默认 48dp 最小触控撑高行，TV 首页压到 26dp 让位海报墙 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactChips(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) { content() }
}

// 顶栏小钮（用户 2026-11-03：去图标改单行文字更省位；源钮保留图标与文字横排；internal 供资源口顶栏复用同款）
@Composable
internal fun FuncCard(icon: String?, name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Text(icon, fontSize = 15.sp, maxLines = 1)
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (icon == null) Modifier else Modifier.widthIn(max = 110.dp),
            )
        }
    }
}

/** 只读状态小钮（网络/时钟）：与 FuncCard 同款式纯文字（用户 2026-11-03；internal 供资源口顶栏复用同款） */
@Composable
internal fun StatCard(name: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/** 网络+时钟并排小钮（用户 2026-11-04 资源口顶栏对齐点播：与首页同款自绘状态栏，逻辑同 BrowseScreen 顶栏） */
@Composable
internal fun NetClockPair() {
    val context = LocalContext.current
    val conn = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    var netLabel by remember { mutableStateOf("…") }
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val caps = conn?.activeNetwork?.let { conn.getNetworkCapabilities(it) }
            netLabel = when {
                caps == null -> "离线"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> "已连接"
            }
            clockText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            delay(20_000)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatCard(netLabel)
        StatCard(clockText)
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
