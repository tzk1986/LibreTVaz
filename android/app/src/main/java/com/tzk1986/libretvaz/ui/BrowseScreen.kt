package com.tzk1986.libretvaz.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.border
import com.tzk1986.libretvaz.data.CmsParser
import com.tzk1986.libretvaz.ui.theme.TvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
    var targetPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 地区筛选墙（web Region.PRESETS）：分类命中预设时按 Region.of 客户端过滤，null=全部
    // 用户 2026-11-05 底部筛选面板：预设选择进程级（重开面板显上次选中，刷新/选全部才重置）
    var preset by WallMem.typePreset
    // web Vod.pSrv：预设命中分类树地区子分类→服务端按这些 id 合并拉取（三级 (a)）；null=客户端 (b)
    var pSrv by WallMem.typePresetSrv
    // 服务端二级筛选（web Vod.sub/area/year/lang + buildFilters）：子类覆盖 t，扩展值拼 &area=&year=&lang=
    var subId by remember { mutableStateOf<String?>(null) }
    var areaSel by remember { mutableStateOf<String?>(null) }
    var yearSel by remember { mutableStateOf<String?>(null) }
    var langSel by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf<VodFilters?>(null) } // web Vod.filters：pg=1 响应后重建
    // ===== B10 聚合墙（web Home.aggMode/runAggWall/aggLoadMore 等价）：首启默认聚合，源弹窗可随时切回单源 =====
    var aggMode by remember { mutableStateOf(settings.aggMode) }
    // 用户 2026-11-04 修复返回路径：页签态提升到进程级（进详情/播放返回不再复位「最新」，回原分类如电视剧）
    var aggCat by WallMem.browseCat // -1=最新；0..3=固定四大类
    var aggPreset by WallMem.aggPreset // 地区预设（标签, 地区集）——进程级，同 preset 理由
    // 用户 2026-11-06 走查 #11：返回保墙态。wallKey=墙记忆槽位主键——聚合含源池指纹（池变即换 key），
    // 单源含 si/type/preset；nonce 不入 key（🔄=干净首屏）；二级筛选值/页码为组合级复位，跨不到返回故不入
    val srcFp = remember(sources) { sources.joinToString(";") { it.url } }
    val wallKey = if (aggMode) "agg|$aggCat|${aggPreset?.first ?: ""}|$srcFp"
    else "src|$sourceIdx|${type?.id ?: ""}|${preset?.first ?: ""}"
    // 走查 #11：重建首帧直接种回 keep 墙（若等 effect seed，空墙先量一次会把 gridState 起始滚动钳回 0）
    var items by remember { mutableStateOf(WallMem.browseKeep.value?.takeIf { it.first == wallKey && it.second.isNotEmpty() }?.second ?: emptyList()) }
    var showFilters by remember { mutableStateOf(false) } // web Vod.showFilters：再点当前分类=展开/收起筛选行
    // 用户 2026-11-05：面板打开即把按键焦点移入，遥控器方向键可直接选筛选项；BACK 收面板
    val panelFocus = remember { FocusRequester() }
    var aggPage by remember { mutableIntStateOf(1) }
    var aggMore by remember { mutableStateOf(false) }
    var aggLoadingMore by remember { mutableStateOf(false) }
    var wallSeq by remember { mutableIntStateOf(0) } // 墙轮次 token：切类/换源/离页后作废旧轮结果
    // S4 窗口渲染：聚合墙每次只挂 240 部，哨兵本地展开零请求（web Vod.win 等价）
    // 终审 #2：返回重建按 keep 墙体量播种，防止已展开的长墙被 240 截断回缩
    var aggWin by remember { mutableIntStateOf(maxOf(240, WallMem.browseKeep.value?.takeIf { it.first == wallKey }?.second?.size ?: 0)) }
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

    // 用户 2026-11-05：筛选面板打开后焦点入首个筛选项。AnimatedVisibility+LazyRow 进场组合有先后、
    // 触屏点开后触摸模式也会拒焦——有限重试；遥控器任意按键退出触摸模式后下一跳即落焦
    LaunchedEffect(showFilters) {
        if (showFilters) {
            for (i in 0 until 15) {
                delay(150)
                val ok = runCatching { panelFocus.requestFocus() }.getOrDefault(false)
                if (ok) break
            }
        }
    }

    // web resetFilterVals：子类/扩展值/地区预设一并清
    fun resetFilterVals() {
        subId = null; areaSel = null; yearSel = null; langSel = null; preset = null; pSrv = null; filters = null
    }
    // 用户 2026-11-05 底部筛选面板「切换 tab 不清筛选」：跨分类只清依赖分类树的值，地区预设(preset/pSrv)保留
    fun resetTypeFilterVals() {
        subId = null; areaSel = null; yearSel = null; langSel = null; filters = null
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
        // 走查 #11：单源墙 key 命中=返回重建（items 已在 remember 初始播种），tp=1 网络回来照旧整体替换，
        // 只消除白屏与滚动复位；命中时不再叠 loading 菊花
        val keepHit = WallMem.browseKeep.value?.takeIf { it.first == wallKey && it.second.isNotEmpty() }?.second
        if (keepHit != null && items.isEmpty()) items = keepHit
        loading = keepHit == null
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
                if (targetPage == 1 && fresh.isEmpty() && cachedShown) {
                    WallMem.browseKeep.value = wallKey to items // 走查 #11：缓存首屏也回存
                    loading = false; return@LaunchedEffect
                } // 拉新为空保留缓存首屏
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
        // 终审 #4：拼音派生 CPU 重（8000 词条×N 名）移出主线程
        if (page.isNotEmpty()) withContext(Dispatchers.Default) { VocabPools.get(context).addWords(page.map { it.name }, false) } // 进墙片名入点播池词库
        if (targetPage == 1) {
            // web loadVod pg===1：sortByYearDesc——首绘年降序最新优先（同年保持源序），增量轮追加队尾不重排
            // 终审 #2：keep 命中=返回重建，tp1 不再整体替换（多页墙不塌回首屏、滚动位不钳）；#1：firstWallOf 同 id 去重防 grid 重复 key 闪退
            if (keepHit == null) items = Agg.firstWallOf(page)
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
        if (page.isEmpty() && targetPage == 1 && keepHit == null) { // 终审 #2：已有保墙时空回不盖 error
            error = if (type == null) "源未返回最近更新数据，可点「点播」按分类浏览" else "该分类暂无数据，源可能不支持分类列表"
        }
        WallMem.browseKeep.value = wallKey to items // 走查 #11：单源墙统一出口回存
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
        // 走查 #11：key 命中=返回重建，恢复原墙不清空（🔄/切类/换预设/源池变更自然换 key→干净首屏）；
        // drawn/firstDrawn 播种，本轮 mergeFrom 走增量分支只补没上墙的片名
        val keepHit = WallMem.browseKeep.value?.takeIf { it.first == wallKey && it.second.isNotEmpty() }?.second
        val pool = Agg.poolOf(sources) { s -> Agg.healthMs(health, s) }
        val cat = aggCatOf()
        val ttl = aggTtl()
        items = keepHit ?: emptyList(); aggPage = 1; aggMore = false; aggLoadingMore = false
        error = null
        loading = pool.isNotEmpty() && keepHit == null
        if (pool.isEmpty()) { error = "聚合池为空，请先在「源管理」导入点播源"; loading = false; return@LaunchedEffect }
        // 队列化渲染：首绘整体年降序一次，此后每轮只把没上墙的片名追加队尾（已显示条目不动）
        val drawn = if (keepHit != null) HashSet(keepHit.map { Agg.nameKey(it) }) else HashSet()
        var firstDrawn = keepHit != null
        var mergedOnce = keepHit != null
        // 用户 2026-11-06 走查 #4：mergeShards（读盘+JSON 编解码）下 IO 线程，状态写回恒在 Main
        suspend fun mergeFrom(c: AggCat?): Boolean {
            val merged = withContext(Dispatchers.IO) { Agg.mergeShards(pool, c) }
            if (merged.isEmpty()) return false
            withContext(Dispatchers.Default) { VocabPools.get(context).addWords(merged.map { it.name }, false) } // 新片名进点播池词库；终审 #4：拼音派生移出 Main
            withContext(Dispatchers.Main) {
                error = null // 有数据上墙即撤「聚合无数据」文案（地区增补到达同适用）
                if (!firstDrawn) {
                    firstDrawn = true; items = Agg.yearDesc(merged)
                    merged.forEach { drawn.add(Agg.nameKey(it)) } // minor#5：仅首绘播种 drawn（增量分支 filter 判重时已顺带入集，原尾随全量 forEach 是冗余拷贝）
                } else {
                    val fresh = merged.filter { drawn.add(Agg.nameKey(it)) }
                    if (fresh.isNotEmpty()) items = items + fresh
                }
                aggMore = true
                mergedOnce = true
                WallMem.browseKeep.value = wallKey to items // 走查 #11：items 每次变更后同步回存
            }
            return true
        }
        mergeFrom(cat)
        val need = pool.take(Agg.POOL_SIZE).filterNot { Agg.fresh(it, cat, ttl) }.toMutableList()
        coroutineScope {
            need.forEach { s ->
                launch(Dispatchers.IO) {
                    val got = runCatching { Agg.fetchSource(repo, s, cat, 1) }.getOrDefault(emptyList())
                    Agg.putShard(s, cat, got)
                    if (my == wallSeq) mergeFrom(cat) // 一源返回就重画，不等最慢源
                }
            }
        }
        if (my != wallSeq) return@LaunchedEffect
        loading = false
        // 方案A（用户 2026-11-06 批准，铁律放宽为「chip 切换零阻塞、允许后台定向增补」）：过滤态先秒併已有
        // @区 分片，再低并发(2)按 hasRegionKid 优先序拉各源地区子类逐源追加墙尾——首屏仍是「全部」分片客户端
        // 过滤秒显，增补只增量补新片名，不改道、不清墙（take(3) 拉不到日剧等第 4+ 位子类，实测根因）
        val rc = aggPreset?.second?.let { if (it.isNotEmpty()) Agg.regionCatOf(cat, it) else null }
        if (rc != null) {
            launch {
                mergeFrom(rc)
                val caps = pool.mapNotNull { s ->
                    runCatching { if (Agg.hasRegionKid(Agg.typesOf(repo, s), rc)) s.url else null }.getOrNull()
                }.toSet()
                coroutineScope {
                    val sem = Semaphore(2)
                    Agg.preferRegionFirst(pool, caps).filterNot { Agg.fresh(it, rc, ttl) }.forEach { s ->
                        launch(Dispatchers.IO) {
                            sem.withPermit {
                                if (my != wallSeq) return@withPermit
                                val got = runCatching { Agg.fetchSource(repo, s, rc, 1) }.getOrDefault(emptyList())
                                Agg.putShard(s, rc, got)
                                if (my == wallSeq) mergeFrom(rc)
                            }
                        }
                    }
                }
            }
        }
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
                        launch(Dispatchers.IO) {
                            val got = runCatching { Agg.fetchSource(repo, s, cat, 1) }.getOrDefault(emptyList())
                            Agg.putShard(s, cat, got)
                            if (my == wallSeq) mergeFrom(cat)
                        }
                    }
                }
                if (my == wallSeq) Agg.prefetch(repo, pool, aggTtl()) { my == wallSeq }
            }
        }
    }

    // S4 无限下拉：各源同分类 pg+1 追加进分片（dedupById+800 封顶）。逐源到达即增量上墙+单源 9s 封顶
    // （用户 2026-11-04 复刻资源口提速：不再整轮等最慢源，快源 1~3s 先出图）
    fun aggLoadMore() {
        if (!aggMode || !aggMore || aggLoadingMore) return
        if (aggPage >= Agg.FUSE_ROUND) { aggMore = false; return } // 极端保险丝；判底=全池 800 封顶（空轮仍继续，用户 2026-11-04）
        val refocus = sentinelFocused
        val my = wallSeq
        val pg = aggPage + 1
        // 过滤态续轮走 @区 定向分片（拉日剧第 N 页远比拉国产剧第 N 页有效）；「全部」labs 空=regionCatOf 原样返回基础 cat
        val cat = Agg.regionCatOf(aggCatOf(), aggPreset?.second ?: emptyList())
        val pool = Agg.poolOf(sources) { s -> Agg.healthMs(health, s) }
        aggLoadingMore = true
        scope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            pool.forEach { s ->
                launch(Dispatchers.IO) {
                    val more = runCatching {
                        kotlinx.coroutines.withTimeout(9_000) { Agg.fetchSource(repo, s, cat, pg) }
                    }.getOrDefault(emptyList())
                    if (more.isNotEmpty()) Agg.appendShard(s, cat, more)
                    if (my != wallSeq) return@launch
                    // 本源一回就重并上墙（用户 2026-11-06 走查 #4：mergeShards 下 IO，主线程只写状态）
                    val all = withContext(Dispatchers.IO) { Agg.mergeShards(pool, cat) }
                    withContext(Dispatchers.Main) {
                        val onWall = items.mapTo(HashSet()) { Agg.nameKey(it) }
                        val added = all.filter { onWall.add(Agg.nameKey(it)) } // 本轮见过的不再上墙
                        if (added.isNotEmpty()) {
                            items = items + added
                            launch(Dispatchers.Default) { VocabPools.get(context).addWords(added.map { it.name }, false) } // 终审 #4：词库派生后台化
                            WallMem.browseKeep.value = wallKey to items // 走查 #11：续轮追加同样回存
                        }
                    }
                    if (done.incrementAndGet() == pool.size) {
                        aggLoadingMore = false
                        aggPage = pg
                        // 空轮不判到底（用户 2026-11-04：下拉要继续拉更多——CMS 尾页常整页重复、后页反有新数据；
                        // 仅当全池分片都到 800 封顶或触 FUSE 轮才停）；地区 chip 下短过滤墙底栏恒可见
                        // →AutoLoadMore 自链「一直加载中」，10 轮封顶（走查 2026-11-06 日剧案，R8 单源护栏同构）
                        aggMore = Agg.aggMoreContinue(aggPage, aggPreset != null,
                            pool.any { (Agg.shards.getRaw(Agg.key(it, cat))?.v?.size ?: 0) < Agg.DEPTH })
                        if (refocus) {
                            delay(80) // 追加重绘后回焦哨兵，支持连按 OK 无限下刷
                            runCatching { sentinelFr.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    val scr0 = remember { WallMem.browseScroll.value?.takeIf { it.first == wallKey }?.let { it.second to it.third } ?: (0 to 0) }
    val gridState = rememberLazyGridState(scr0.first, scr0.second)
    LaunchedEffect(wallKey) {
        var first = true
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect {
                // minor#6：切 key 后首发=同一 gridState 的旧墙滚动位瞬态值，写入会污染新 key 的恢复点（items 钳位虽自愈，跳过更干净）
                if (first) { first = false; return@collect }
                WallMem.browseScroll.value = Triple(wallKey, it.first, it.second)
                delay(250) // 简易节流：延迟期间滚动中间态被 conflated 丢弃
            }
    }
    // R8 三级 (b)：仅客户端过滤模式（preset 且无 pSrv）生效，UNKNOWN 保守剔除并计数（web drawVodGrid）
    val prNow = preset
    val regionSel = if (prNow != null && pSrv == null) prNow.second else emptyList()
    // 用户 2026-11-06 走查 #10：地区过滤每次重组全表重算 → 按源数据+选择 remember（unknownCnt 同块赋值语义不变）
    var unknownCnt = 0
    val shown = remember(items, regionSel) {
        var cnt = 0
        if (regionSel.isEmpty()) items else run {
            val l = ArrayList<VodSummary>()
            for (it in items) {
                val r = Region.of(it)
                if (r == "UNKNOWN") { cnt++; continue }
                if (regionSel.contains(r)) l.add(it)
            }
            unknownCnt = cnt
            l
        }
    }
    val aggLabs = aggPreset?.second ?: emptyList()
    val aggShown = remember(items, aggLabs) {
        if (aggLabs.isEmpty()) items
        else items.filter { aggLabs.contains(Region.of(it)) }
    }
    // S4 窗口渲染：墙内条目先只挂 aggWin 部；哨兵「展开更多」本地放量零请求，展完才联网续轮（web moreBtn.onclick 等价）
    val aggExpandable = aggShown.size > aggWin
    val aggView = if (aggExpandable) aggShown.take(aggWin) else aggShown
    fun aggMoreOrExpand() { if (aggExpandable) aggWin += 240 else aggLoadMore() }
    // 顶栏收缩（用户 2026-11-04 复刻资源口方案）：顶栏=墙 item0（仅首页在）、分类 tab=stickyHeader 吸顶，
    // AutoLoadMore 绝对索引偏移=墙前 header 数（atHomeNow 时 +2=顶栏+吸顶，否则 +1=吸顶）；空墙传 0 保持零触发
    val atHomeNow = if (aggMode) aggCat == -1 else type == null
    val hdrOff = if (atHomeNow) 2 else 1
    AutoLoadMore(
        gridState,
        if (aggMode) { if (aggView.isEmpty()) 0 else aggView.size + hdrOff } else { if (shown.isEmpty()) 0 else shown.size + hdrOff },
        if (aggMode) aggLoadingMore else loading,
        if (aggMode) aggExpandable || aggMore else canLoadMore,
    ) { if (aggMode) aggMoreOrExpand() else targetPage++ }

    /** 🔄 刷新钮（web vodRefresh 等价）：只清点播相关分片（历史/收藏/测速不动），清后重拉本页 */
    fun refreshPage() {
        // 用户 2026-11-05 底部筛选面板：刷新=唯一整体重置口（筛选项回「全部」并收起面板）
        showFilters = false
        aggPreset = null
        resetFilterVals()
        if (aggMode) Agg.purgeWallShards() else source?.let { Agg.purgeTypesOf(it.url); Agg.purgeMrgOf(it.url) }
        WallMem.browseKeep.value = null; WallMem.browseScroll.value = null // 走查 #11：刷新=唯一整体重置口，连墙记忆一并清（nonce 不入 key，否则清不了旧墙）
        Toast.makeText(context, "已清本页缓存，重新拉取中…", Toast.LENGTH_SHORT).show()
        nonce++
    }

    // 分层返回（web 顶栏式）：弹窗开→关窗；未滚到顶→回顶；到顶且无弹窗→双击返回退出 app
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 } }
    BackHandler {
        when {
            showSourcePicker -> showSourcePicker = false
            // 用户 2026-11-05：底部筛选面板打开时返回键只收面板（不清筛选值，重开显上次选中）
            showFilters -> showFilters = false
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

    // 顶栏（用户 2026-11-03：功能行并入一行、源钮同款小方块，一行＝源钮+刷新+导航小方块（横滚）+ 右网络·时钟，
    // 状态栏已隐藏此处自绘替代；换源回首页切）；2026-11-04 收缩优化（复刻资源口方案）：改由墙 item0 承载，
    // 随下拉逐渐隐去、回顶自然复原——抽成 browseTopBar 组合 lambda，由 grid/空态两分支各自调用
    // web 导航「收藏·N更」徽标：回首页重读更新数（checkKeeps 由收藏页触发写库，此处只展示）
    val keepUpdCount = remember { mutableIntStateOf(0) }
    LaunchedEffect(atHomeNow) {
        if (atHomeNow) keepUpdCount.intValue = KeepStore(context).updatedCount(false)
    }
    val browseTopBar: @Composable () -> Unit = {
            // 常用置顶（用户 2026-11-03）：历史→搜索→刷新→其余按导航栏存档序
            // 用户 2026-11-06 走查 #9：每次重组重解析 tabsJson → 按存档串 remember（LazyListScope 顶层不可 remember，上提至组合体）
            val cards = remember(settings.tabsJson) { orderedCards(settings) }
            val pinned = listOf("hist", "search").mapNotNull { id -> cards.firstOrNull { it.id == id } }
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
                    itemsIndexed(pinned, contentType = { _, _ -> "func" }) { _, card ->
                        FuncCard(icon = null, name = card.title, onClick = { go(card.id) })
                    }
                    item { FuncCard(icon = null, name = "刷新", onClick = { refreshPage() }) }
                    itemsIndexed(cards.filter { it.id != "hist" && it.id != "search" }, contentType = { _, _ -> "func" }) { _, card ->
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

    // 底部筛选面板（用户 2026-11-05 照 TVBox 截图2）：再点当前 tab 弹出（首点只切类），弹出即抢焦点便于遥控器；选中墙即时过滤且面板不收起；
    // 收起只走返回键/再点当前 tab；重开显上次选中；切分类不清地区预设；仅刷新或选「全部」重置
    // 样式（用户 2026-11-05 追加）：面板无实底、海报墙直接透出；chip=半透明药丸
    val filterPanel: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        CompactChips {
            if (aggMode) {
                val aggPresets = Agg.AGG_CATS.getOrNull(aggCat)?.let { Region.PRESETS[it.key] }
                if (!aggPresets.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelLabel("地区")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(aggPresets) { idx, (pLabel, regions) ->
                                PanelChip(
                                    selected = (aggPreset?.first ?: "全部") == pLabel,
                                    onClick = { aggPreset = if (regions.isEmpty()) null else pLabel to regions },
                                    label = pLabel,
                                    fr = if (idx == 0) panelFocus else null, // 面板打开首项即焦点，遥控器方向键可用
                                )
                            }
                        }
                    }
                }
            } else {
                val curType = type
                if (curType != null) {
                    val f = filters
                    // web presetKeyForTab：沿 pid 上溯根名+本名匹配预设族（「电影片」「电视剧(年)」等别名也能命中）
                    val presets = Agg.presetKeyOf(curType, types)?.let { Region.PRESETS[it] }
                    val hasGrps = f != null && !f.isEmpty()
                    if ((presets == null || presets.isEmpty()) && !hasGrps) {
                        PanelLabel("该分类不支持二级筛选（源未提供子分类或扩展字段）", small = false)
                    } else {
                        val yr = java.time.LocalDate.now().year.toString()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val label: @Composable (String) -> Unit = { s -> PanelLabel(s) }
                            // ① 地区预设：树里有地区子分类→服务端合并 (a)，否则客户端 Region.of 兜底 (b)；
                            //   置灰=无命中且已载条目无匹配亦无 UNKNOWN（web L1743）；选中面板不收起（用户 2026-11-05）
                            if (!presets.isNullOrEmpty()) {
                                item { label("地区") }
                                itemsIndexed(presets) { idx, (pLabel, regions) ->
                                    val pOff = regions.isNotEmpty() &&
                                        Agg.presetIdsOf(regions, curType, types) == null &&
                                        items.isNotEmpty() &&
                                        !items.any { val r = Region.of(it); r == "UNKNOWN" || regions.contains(r) }
                                    PanelChip(
                                        selected = (preset?.first ?: "全部") == pLabel,
                                        onClick = {
                                            if (pOff) {
                                                Toast.makeText(context, "该源分类未收录此地区内容", Toast.LENGTH_SHORT).show()
                                                return@PanelChip
                                            }
                                            preset = if (regions.isEmpty()) null else pLabel to regions
                                            pSrv = (preset)?.let { Agg.presetIdsOf(it.second, curType, types) }
                                            targetPage = 1
                                        },
                                        label = pLabel,
                                        dim = pOff,
                                        fr = if (idx == 0) panelFocus else null,
                                    )
                                }
                            }
                            // ② 子类组：pid 命中当前分类的子类，点选=服务端 t 覆盖（web Vod.sub）
                            if (f != null && f.subs.isNotEmpty()) {
                                item { label("子类") }
                                item {
                                    PanelChip(selected = subId == null, onClick = { subId = null; targetPage = 1 }, label = "全部")
                                }
                                items(f.subs.take(30)) { sub ->
                                    PanelChip(
                                        selected = subId == sub.id,
                                        onClick = { subId = sub.id; targetPage = 1 },
                                        label = sub.name,
                                    )
                                }
                            }
                            // ③④⑤ 扩展组（web grp()）：全部+选项≤30，同项再按=取消；年份插本年去重；选中面板不收起（用户 2026-11-05）
                            fun grpItems(name: String, opts: List<String>, sel: String?, clear: () -> Unit, pick: (String) -> Unit) {
                                item { label(name) }
                                item { PanelChip(selected = sel == null, onClick = { clear(); targetPage = 1 }, label = "全部") }
                                items(opts.take(30)) { v ->
                                    PanelChip(
                                        selected = sel == v,
                                        onClick = { if (sel == v) clear() else pick(v); targetPage = 1 },
                                        label = if (v == yr) "本年($yr)" else v,
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
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    // 紧凑化（用户 2026-11-03 TV/投影要求）：列间距 8→4dp，chip 行最小触控 48→26dp，标题降字号——纵向全让海报墙
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 点播即首页（web P8）：无源时保留顶栏骨架，内容区仅提示（对齐 web typeRow 文案）
        if (sources.isEmpty()) {
            if (atHomeNow) browseTopBar()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("无点播源：在「源管理」导入订阅或添加内置源", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                TmBtn(onClick = onSources) { Text("源管理") }
            }
            return@Column
        }

        // 海报墙（顶栏收缩后顶栏/tab 均为墙内 item：item0=顶栏、stickyHeader=分类行）
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (atHomeNow) item(span = { GridItemSpan(maxLineSpan) }) { browseTopBar() }
            // 分类 tab 改 stickyHeader 吸顶常驻（用户 2026-11-04 复刻资源口方案；参考图定稿：吸顶行不留底带，
            // chip 自身镂空透明、选中实心，其余全透海报墙）；error 提示并入吸顶列常驻可见
            stickyHeader {
                CompactChips {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
        if (aggMode) {
            // 聚合墙分类标签（web AGG_CATS：最新+固定四大类，不依赖单源分类树）
            val aggIdx = aggCat
            // TVBox 复刻（用户 2026-11-09 需求 b）：进墙页焦点直达当前选中 tab 而非「最新」；
            // 触屏模式 requestFocus 自然失败无副作用；仿 panelFocus 有限重试（LazyRow 进场+恢复焦点时序）
            val tabFrs = remember { List(Agg.AGG_CATS.size + 1) { FocusRequester() } }
            LaunchedEffect(Unit) {
                for (i in 0 until 8) {
                    delay(150)
                    val ok = runCatching { tabFrs[(aggCat + 1).coerceAtLeast(0)].requestFocus() }.getOrDefault(false)
                    if (ok) break
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // 切「最新」只收起面板；预设值保留（再点分类 tab 显上次选中，用户 2026-11-05）
                    WallChip(selected = aggCat == -1, onClick = {
                        if (aggCat != -1) { aggCat = -1; showFilters = false }
                    }, label = "最新", fr = tabFrs[0])
                }
                items(Agg.AGG_CATS) { c ->
                    val i = Agg.AGG_CATS.indexOf(c)
                    WallChip(
                        fr = tabFrs[i + 1],
                        selected = aggIdx == i,
                        onClick = {
                            // 用户 2026-11-05 底部面板：首点=只切类不开面板；再点当前 tab=弹面板+焦点入面板，BACK 收起；切类不清筛选值
                            if (Region.PRESETS[c.key]?.isNotEmpty() == true) {
                                if (aggIdx == i) showFilters = !showFilters
                                else { aggCat = i; showFilters = false }
                            } else if (aggIdx != i) { aggCat = i; showFilters = false }
                        },
                        label = c.key,
                    )
                }
            }
            // 地区预设行已挪底部面板（用户 2026-11-05 照 TVBox 截图2；见 filterPanel）
        } else {
            if (types.isNotEmpty()) {
                val typeList = types
                val currentType = type
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        // web：id=='' 分支恒重拉最新（非 toggle），并收起筛选行
                        WallChip(
                            selected = currentType == null,
                            onClick = { type = null; resetFilterVals(); showFilters = false; targetPage = 1 },
                            label = "更新推荐",
                        )
                    }
                    items(typeList) { t ->
                        WallChip(
                            selected = t.id == currentType?.id,
                            onClick = {
                                // 用户 2026-11-05 底部面板：首点=只切类（保留地区预设，不开面板）；再点当前=弹面板+焦点入面板，BACK 收起
                                if (t.id == currentType?.id) {
                                    showFilters = !showFilters
                                } else {
                                    type = t; resetTypeFilterVals(); showFilters = false; targetPage = 1
                                }
                            },
                            label = t.name,
                        )
                    }
                }
            }

            // 二级筛选行已挪底部面板（用户 2026-11-05 照 TVBox 截图2；见 filterPanel）
        }
                        // 功能行已并入首页顶栏（用户 2026-11-03：源旁同款小方块，空间全让海报墙）；
                        // 分类/预设提示文字已全撤（用户 2026-11-03 定稿）；加载态由墙尾哨兵呈现，error 由吸顶列兜底常驻可见
                        if (error != null && !loading) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (aggMode) {
                // 聚合墙卡片（用户 2026-11-03 要求，偏离 web 点卡直达详情）：只显示海报+名称（数据层
                // mergeShards 已按归一剧名去重），点击=带剧名进搜索页全源聚合，用户再选源播放
                itemsIndexed(aggView, key = { _, it -> "${it.sourceUrl}|${it.vodId}" }, contentType = { _, _ -> "poster" }) { _, item ->
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
                // 用户 2026-11-06 走查 #10：单源墙补 key（与聚合墙同键式）+contentType
                itemsIndexed(shown, key = { _, it -> "${it.sourceUrl}|${it.vodId}" }, contentType = { _, _ -> "poster" }) { _, item ->
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

    // 底部筛选面板浮层（用户 2026-11-05 截图2 样式）：吸底覆盖，不占墙布局空间
    AnimatedVisibility(
        visible = showFilters,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) { filterPanel() }
    }

    if (showSourcePicker) {
        val pickerSources = sources
        AlertDialog(
        modifier = Modifier.width(tmDialogW()),
        properties = tmDialogProps,
            onDismissRequest = { showSourcePicker = false },
            confirmButton = {
                TmBtn(onClick = { showSourcePicker = false }) { Text("关闭") }
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
        canMore -> TmBtn(
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

/** 焦点/滚动接近末行时自动翻页（FongMi onLoadMore 行为；用户 2026-11-04：遥控下按/手机上滑即拉更多——提前一屏行数预取，拉到底数据已备好） */
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
            val vis = gridState.layoutInfo.visibleItemsInfo
            val last = vis.lastOrNull()?.index ?: -1
            val cols = vis.groupBy { it.row }.values.maxOfOrNull { it.size } ?: 1
            last to (itemCount > 0 && canLoadMore && !loading && last >= itemCount - 1 - cols)
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

/** 镂空透明海报墙 chip（用户 2026-11-04 参考图：吸顶行不留任何底带，海报墙全透；
 * 未选中=透明+描边镂空，选中=primaryContainer 实心；去默认 leading 勾位贴参考图） */
@Composable
internal fun WallChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    labelColor: Color = Color.Unspecified,
    fr: FocusRequester? = null,
) {
    // 未选中字加淡投影（用户 2026-11-04：镂空后文字压在亮海报上易失读，阴影兜底）
    val base = LocalTextStyle.current
    // TVBox 复刻（用户 2026-11-09）：遥控焦点态=2dp 白描边+40% 白底（全 app 统一，同 TmChip）；
    // fr=墙页 tab 焦点恢复位（进墙页直达当前选中 tab）
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.onFocusEvent { focused = it.isFocused }
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier),
        leadingIcon = {},
        label = {
            Text(
                label,
                color = labelColor,
                style = if (selected) base else base.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.55f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 6f,
                    ),
                ),
            )
        },
        // M3 1.4.0 无 focusedContainerColor：焦点亮底折进 containerColor（TVBox focused=40% 白底）；
        // 选中底/描边撤 M3 primaryContainer 紫，统一 TvColors 红（用户 2026-11-09 遗留清理）
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (focused) Color(0x66FFFFFF) else Color.Transparent,
            selectedContainerColor = TvColors.Red40,
            disabledContainerColor = Color.Transparent,
        ),
        border = if (focused) BorderStroke(2.dp, Color.White) else BorderStroke(
            1.dp,
            if (selected) TvColors.Red else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
        ),
    )
}

/** 底部筛选面板组名标签（用户 2026-11-05 截图：面板透出海报墙，白字+投影保读） */
@Composable
internal fun PanelLabel(s: String, small: Boolean = true) {
    Text(
        s,
        style = (if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
            .copy(shadow = androidx.compose.ui.graphics.Shadow(
                color = Color.Black.copy(alpha = 0.7f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 8f,
            )),
        color = Color.White,
    )
}

// PanelChip 已并入 TmChip.kt（全 app 统一黑底 chip）

// 顶栏小钮（用户 2026-11-03：去图标改单行文字更省位；源钮保留图标与文字横排；internal 供资源口顶栏复用同款）
// 用户 2026-11-05 终改：底色黑半透在纯黑背景上完全隐形——改白 20% 半透，与全 app 统一按钮（PillButton/TmBtn）同款
@Composable
internal fun FuncCard(icon: String?, name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0x33FFFFFF),
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
        color = Color(0x33FFFFFF), // 用户 2026-11-05：与 FuncCard 同步改白 20%（纯黑底上黑半透不可见）
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
internal fun PosterCard(item: VodSummary, onClick: () -> Unit, siteLabel: String? = null, codeBadge: Boolean = false) {
    // 用户 2026-11-10 修订 #133：焦点=仅白描边（去红罩）+整卡 1.08 放大抽屉外推，移焦还原
    var cardFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .onFocusEvent { cardFocused = it.isFocused }
            .zIndex(if (cardFocused) 1f else 0f)
            .graphicsLayer {
                scaleX = if (cardFocused) 1.08f else 1f
                scaleY = if (cardFocused) 1.08f else 1f
            }
            .clickable(onClick = onClick),
    ) {
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
            // 用户 2026-11-06 截图版式：年份放海报左上（黑透明白字圆角徽章），番号徽章（2026-11-04 搜索增强）排其右；
            // 追加：季数「第X季」单独成徽章放年份下方（同款 55% 黑透样式）
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.year?.takeIf { it.isNotBlank() && it != "0" }?.let { y ->
                        Text(
                            text = y,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (codeBadge) CmsParser.codeRaw(item.name)?.let { c ->
                        Text(
                            text = c.uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                CmsParser.seasonOf(item.name)?.let { s2 ->
                    Text(
                        text = s2,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            // 用户 2026-11-06：来源名徽章移至右上角（底部让位给信息带）
            siteLabel?.let { t ->
                Text(
                    text = t,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 用户 2026-11-06 海报墙改版（指定截图效果=有意偏离 web「名在图下」版式）：
            // 名称黑透带贴满海报底边整幅宽；remarks 徽章独立在带上方靠左，与带、年份同 55% 黑透
            Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                item.remarks?.takeIf { it.isNotBlank() }?.let { r ->
                    Text(
                        text = r,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 用户 2026-11-06：左右各 6dp 外边距——徽章最宽=海报宽−12dp，超长提前省略，不再顶出右边
                        modifier = Modifier
                            .padding(start = 6.dp, end = 6.dp, bottom = 3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                // 用户 2026-11-06 追加：名称居中（weight fill=false 保持组内居中且超长省略）；
                // A+B 截断优化：显示名先经 displayTitle 裁剪（季数/结尾年份已由徽章承载），仍超长允许两行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    AdultTag(adultOf(item))
                    Text(
                        text = CmsParser.displayTitle(item.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (cardFocused) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}
