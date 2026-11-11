package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CacheTiers
import com.tzk1986.libretvaz.data.CmsParser
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HealthStore
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.perf.LineSpeed
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 快搜弹窗（对应 web openFindBox）：片名打散为候选词 chips（relCandidates：核心词→前缀短语→核心+季部→原名），
 * 默认按原名自动全源搜索＝旧「搜本作」，点候选词即换词重搜；点结果跨源跳转续播。Detail/Player 两处复用。
 * zone=true 走专区池路由（web collectSources(zone)）。用户 2026-11-05 终定（看过 TVBox 实机图后）：
 * 弹窗不放搜索框——打开即按传入片名直出结果，换词只点关联词 chip；自由改词去首页「搜索」页。
 * matchBest=true＝换源弹框（用户 2026-11-03）：每源仅取严格同名一条（等值→包含），增量上屏，不写搜索分片避免污染聚合结果；
 * excludeUrl 排除当前源。BACK 由 Dialog 自身消费关闭回上一页。
 * 用户 2026-11-05：行内逐行测速（⚡角标）已拿掉——换源只需同名命中即点，测速在详情页线路行做。
 * 用户 2026-11-06 批准重加（改两档自动，无需手点）：即时档=Health.ms 行首圆点（绿≤500/黄≤1500/红=慢或死源沉底）；
 * 后台档=每行取详情首线首集链接 probeTtfb（A2≤2s）标「⚡快/中/慢」，epoch 变更或关框即停。
 * 用户 2026-11-05 合并改造：换源模式（matchBest）也显示关联词 chips；点词/点「搜索」即切到普通全源搜（best=false），
 * 原「换源」「搜关联」两入口在此合为一个弹窗。
 * 用户 2026-11-05 终改：按 TVBox「快搜」参考图重做——钮与弹窗更名「快搜」。结果区最终定稿＝参考图同款
 * 单列文字行列表（不用海报墙/缩略图，图床 DNS 污染时也不受影响）：顶部提示「其他数据源中的相关资源，
 * 没有找到可以尝试选词重搜」+ 当前词 chip + 关联词 chips；每行一条资源＝🎥 ｜源名｜ 资源 片名 类型 状态，
 * 整行可点＝换源/跳详情续播；竖滚列表撑满弹窗宽度。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FindSourceDialog(
    store: SourceStore,
    title: String,
    onDismiss: () -> Unit,
    onPick: (VodSummary) -> Unit,
    zone: Boolean = false,
    matchBest: Boolean = false,
    excludeUrl: String? = null,
) {
    val context = LocalContext.current
    val repo = remember { CmsRepository() }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    // 用户 2026-11-05：换源模式也出关联词（合并入快搜弹窗）
    val candidates = remember(title) { PerfCore.relCandidates(title) }
    var kw by remember { mutableStateOf(title) }
    var best by remember { mutableStateOf(matchBest) } // true=换源（每源同名一条+测速）；点关联词/全源搜索后转 false
    var results by remember { mutableStateOf<List<VodSummary>?>(null) }
    var searching by remember { mutableStateOf(title.isNotBlank()) } // 空词（专区找片入口）不自动搜，手输后点钮触发
    var hint by remember { mutableStateOf<String?>(null) }
    var runToken by remember { mutableIntStateOf(0) }
    val found = sortedMapOf<Int, List<VodSummary>>() // si→该源命中（web bySi：整数值键自动按源序展平）
    // 用户 2026-11-06 批准的快搜两档自动标记：即时档源健康 + 后台档首字节探测表（"url|id" → 快/中/慢）
    val health = remember { HealthStore(context) }
    val probeMarks = remember { mutableStateMapOf<String, String>() }
    val probed = remember { mutableSetOf<String>() } // 增量 flush 防重复起探
    val semProbe = remember { Semaphore(3) }

    /** 后台档单行探测：详情（缓存优先）取首线首集链接 → probeTtfb 分档；epoch 外/关框均停写 */
    fun probeRow(ep: Int, hit: VodSummary) {
        val k = "${hit.sourceUrl}|${hit.vodId}"
        if (probeMarks.containsKey(k) || !probed.add(k)) return
        scope.launch(Dispatchers.IO) {
            semProbe.withPermit {
                if (ep != runToken) return@withPermit
                val src = store.vodSources().firstOrNull { PerfCore.norm(it.url) == PerfCore.norm(hit.sourceUrl) }
                val link = src?.let { s ->
                    (repo.detailCached(s, hit.vodId) ?: runCatching { withTimeout(8_000L) { repo.detail(s, hit.vodId) } }.getOrNull())
                        ?.lines?.firstOrNull()?.list?.firstOrNull()?.link
                }
                val ms = link?.takeIf { it.isNotEmpty() }?.let { LineSpeed.probeTtfb(it) }
                if (ep != runToken) return@withPermit
                probeMarks[k] = when {
                    ms == null || ms > 1500 -> "慢"
                    ms <= 500 -> "快"
                    else -> "中"
                }
            }
        }
    }

    /** 渐进渲染+SWR（web openFindBox.runFind 等价）：分片先上屏 → 首批 top N 逐源到达即追加 → 1.2s 次批；epoch 丢弃迟到 */
    fun runFind(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        val ep = runToken + 1; runToken = ep
        searching = true; results = null; hint = null
        scope.launch {
            val exN = excludeUrl?.let { PerfCore.norm(it) }
            val ck = PerfCore.searchKey(query)
            // 用户 2026-11-06 走查 #4：取源表+分片盘回灌（shardsOf 含磁盘读+JSON 解码）下 IO，状态写回仍在 Main
            val (items, siOf, splitOpt) = withContext(Dispatchers.IO) {
                val its = store.vodSources().mapIndexed { si, s -> si to s }.filter {
                    it.second.isAdult == zone && (exN == null || PerfCore.norm(it.second.url) != exN)
                } // web collectSources(zone)；换源模式排除当前源
                val m = its.associateBy({ PerfCore.norm(it.second.url) }, { it })
                val sp = if (its.isEmpty()) null
                else PerfCore.splitTtl(shardsOf(ck), CacheTiers.TIERS[settings.cacheTier].search)
                Triple(its, m, sp)
            }
            if (items.isEmpty()) {
                hint = if (zone) "专区池暂无源" else "尚未添加点播源"
                results = emptyList(); searching = false; return@launch
            }
            synchronized(found) { found.clear() }
            val split = splitOpt!!
            val nt0 = CmsParser.normalizeTitle(query)
            (split.fresh.entries + split.stale.entries).forEach { (u, list) ->
                val x = siOf[u] ?: return@forEach
                val l = if (best) {
                    listOfNotNull(
                        list.firstOrNull { CmsParser.normalizeTitle(it.name) == nt0 }
                            ?: list.firstOrNull { CmsParser.normalizeTitle(it.name).contains(nt0) },
                    )
                } else list
                if (l.isNotEmpty()) synchronized(found) { found[x.first] = l }
            }
            fun flush() {
                if (ep != runToken) return
                val snap = synchronized(found) { found.toSortedMap() }
                results = snap.values.flatten().take(30)
                results?.forEach { probeRow(ep, it) } // 后台档：已显示行起探（probed 表去重，增量 flush 只补新行）
            }
            flush()
            suspend fun fire(x: Pair<Int, VodSource>) {
                val nt = CmsParser.normalizeTitle(query)
                val raw = runCatching {
                    withTimeout(8_000L) { repo.searchSourcePage1(x.second, query) }
                }.getOrNull() ?: return
                // 换源模式：每源仅一条严格同名（等值→包含兜底），且不落搜索分片（防污染聚合/SWR）；全源搜（非换源）维持相关性过滤
                val list = if (best) {
                    listOfNotNull(
                        raw.firstOrNull { CmsParser.normalizeTitle(it.name) == nt }
                            ?: raw.firstOrNull { CmsParser.normalizeTitle(it.name).contains(nt) },
                    )
                } else raw.filter { CmsParser.relevantForSearch(it.name, query, settings.fuzzy) }
                if (!best) {
                    val shards = shardsOf(ck)
                    shards[PerfCore.norm(x.second.url)] = PerfCore.Shard(System.currentTimeMillis(), list)
                    persistShards(ck, shards)
                }
                synchronized(found) { found[x.first] = list }
                flush()
            }
            val (first, rest) = PerfCore.splitBatches(items, settings.searchThreads)
            coroutineScope {
                first.forEach { x -> launch(Dispatchers.IO) { fire(x) } }
                if (rest.isNotEmpty()) launch(Dispatchers.IO) {
                    delay(1200)
                    if (ep == runToken) rest.forEach { x -> launch { fire(x) } }
                }
            }
            if (ep != runToken) return@launch
            searching = false
            val r = results ?: emptyList()
            if (r.isEmpty()) hint = "各源均无结果"
        }
    }

    LaunchedEffect(Unit) { runFind(title) }

    // 用户 2026-11-05（image #7 反馈）：AlertDialog 写死的 title/content 大内边距头部占位过大→改 BasicAlertDialog
    // 自绘 Surface（同款 extraLarge 圆角+surfaceContainerHigh 底）；再按参考图定稿：高 0.98×屏、宽 0.7×屏居中、
    // 标题行删除（BACK 关窗）、内边距 10/16dp，纵向空间全让给结果行
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        // 用户 2026-11-03：加宽弹框。平台默认 wrap 宽（usePlatformDefaultWidth）会钳掉 widthIn，必须先放开
        // 用户 2026-11-05 终改：宽=屏宽 70% 居中，高 0.98 与宽解耦
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
      // 用户 2026-11-05 终定（TVBox 实机图对照）：高度折中 0.98（手机可用区≈屏比0.85），与宽度解耦；
      // 用户 2026-11-05 修正：dialog 窗口四边有系统 surfaceInsets(84px)，fillMaxWidth(0.7) 实际只到屏宽 65%——
      // 改按屏宽×0.7 定宽。注意 displayMetrics.widthPixels 在横屏 edge-to-edge 下返回的是扣掉系统区后的
      // app 宽（实测 1980≠物理 2400）；P1-4（2026-11-11）：基准改画布逻辑宽 canvasWdp()——投影 4K 档
      // 原密度 cfg dp 与画布错配 4 倍会溢屏，手机档两者相等；窗口 gr=CENTER 且 insets 左右对称＝物理屏居中
      val scrW = canvasWdp().dp
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.width(scrW * 0.7f).fillMaxHeight(0.98f),
      ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 用户 2026-11-05：标题行（快搜+✕）整体删除——BACK 即关窗（遥控/手机同理），空间全让给结果行。
            // 顶部一行提示语（截图文案，不折行）→ 关联词 chips → 单列文字行结果
            Text(
                "其他数据源中的相关资源，没有找到可以尝试选词重搜" +
                    (if (searching && results != null) "（陆续返回中…）" else if (searching) "…" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // 候选词词流＝web relRow：点选即换词重搜（合并后：点词同时切出换源模式走全源搜），选中高亮
            // 用户 2026-11-05 终定：无搜索框/搜索钮——换词只靠点关联词，自由改词去首页「搜索」页
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { c ->
                    TmChip(
                        selected = c == kw.trim() && !best,
                        onClick = { kw = c; best = false; runFind(c) },
                        label = c,
                    )
                }
            }
            val r = results
            when {
                r == null && searching -> Text("搜索「$kw」中…")
                r == null -> Text("点关联词可全源重搜；需改词请去首页「搜索」页", style = MaterialTheme.typography.bodySmall)
                else -> {
                    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    // 即时档死源沉底（稳定排序：非死源保持源到达序）
                    val ordered = r.sortedBy { if (health.isDead(it.sourceUrl)) 1 else 0 }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(ordered) { hit ->
                            FindTextRow(
                                hit = hit, onClick = { onPick(hit) },
                                healthMs = health.of(hit.sourceUrl)?.ms,
                                dead = health.isDead(hit.sourceUrl),
                                probe = probeMarks["${hit.sourceUrl}|${hit.vodId}"],
                            )
                        }
                    }
                }
            }
        }
      }
    }
}

/** 快搜单列文字行（用户 2026-11-05 参考图定稿）：● 🎥 ｜源名｜ 资源 片名 类型 状态 年份 ⚡档，
 *  整行深底圆角可点；纯文字不依赖图床。源名去掉自带「|资源」尾缀防重复（如「量子|资源」→量子）。
 *  ●=即时档源接口延迟（绿≤500ms 黄≤1500ms 红=慢/死 灰=未测，用户 2026-11-06）；⚡=后台档首字节（快/中/慢） */
@Composable
private fun FindTextRow(hit: VodSummary, onClick: () -> Unit, healthMs: Long?, dead: Boolean, probe: String?) {
    // C34（审计 2026-11-11 #34）：色值收敛 TvColors——红 E05252→SpeedSlow、绿 4CAF50→SpeedFast
    //（与 web sp-* 色阶统一，微色差为收敛代价），未测=白40% GhostBgStrong
    val col = when {
        healthMs == null -> TvColors.GhostBgStrong
        dead || healthMs > 1500 -> TvColors.SpeedSlow
        healthMs <= 500 -> TvColors.SpeedFast
        else -> TvColors.SpeedMid
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(TvColors.ChipBg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("●", style = MaterialTheme.typography.labelSmall, color = col)
        Text("🎥︎", style = MaterialTheme.typography.labelMedium)
        Text(
            "｜${hit.sourceName.removeSuffix("|资源")}｜",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val txt = buildString {
            append("资源 ")
            append(hit.name)
            hit.typeName?.ifBlank { null }?.let { append(" $it") }
            hit.remarks?.ifBlank { null }?.let { append(" $it") }
            hit.year?.ifBlank { null }?.let { append(" $it") }
        }
        Text(txt, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        probe?.let {
            Text(
                "⚡$it", style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = when (it) {
                    "快" -> TvColors.SpeedFast
                    "中" -> TvColors.SpeedMid
                    else -> TvColors.SpeedSlow
                },
            )
        }
    }
}
