package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 指定搜索源：全表下标+源名（null=全部源聚合，web Search.srcSel 等价） */
data class SpecSrc(val si: Int, val name: String)

/** 搜索排序三态（用户 2026-11-06）：点钮循环 DESC→ASC→NONE；枚举序即循环序，默认 DESC */
internal enum class YearOrder { DESC, ASC, NONE }

/** 搜索墙跨页记忆 key：kw+zone+指定源（用户 2026-11-06 返回保滚动不重搜；对齐 browseKeep 语义） */
internal fun collectKey(kw: String, zone: Boolean, srcSel: SpecSrc?): String =
    "$kw|${if (zone) "z" else "v"}|${srcSel?.si ?: "-"}"

// 用户 2026-11-06 走查 #2/#3：搜索分片双层缓存迁 data/SearchShards（LRU 封顶 30 词 + 500ms 防抖后台落盘）。
// 本文件保留同名薄委托（web searchShardSet/localStorage 语义不变，FindSourceDialog/Settings 调用点零改动）
internal fun clearSearchShardsCache() = SearchShards.clearAll()
internal fun shardsOf(ck: String) = SearchShards.shardsOf(ck)
internal fun persistShards(ck: String, map: Map<String, PerfCore.Shard<VodSummary>>) = SearchShards.persistSoon(ck, map)

private const val SOURCE_TIMEOUT_MS = 8_000L

/**
 * 搜索结果页（web renderCollect 等价，布局按用户 2026-11-03 改左源栏+右海报墙两栏）：一源一行渐进追加、epoch 丢弃旧回调（迟到响应不上屏）、
 * SWR 分片缓存先上屏后台替换、全部 Tab 按源到达序拼接（新源进队尾不插前）；
 * 首批 top8 立即发其余 1.2s 次批；单源超时 8s；全源返回仍空且有失败源自动补试一轮；
 * 无结果且纯字母简拼时给拼音推荐 chip（试试搜「XX」? 点击以该词重跑聚合）。
 */
@Composable
fun CollectScreen(
    kw0: String,
    zone: Boolean,
    srcSel: SpecSrc?,
    store: SourceStore,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CmsRepository() }
    val health = remember { HealthStore(context) }
    val settings = remember { SettingsStore(context) }
    val vocab = remember { VocabPools.get(context) }

    // 用户 2026-11-06：搜索墙跨页记忆——同 key（kw|zone|src）命中直接还原快照上屏、跳过重搜；
    // 返回不再复位顶部/重排源栏（对齐聚合墙 browseKeep 走查 #11 模式；点播/资源口两侧共用本组件同受益）
    val ck0 = collectKey(kw0, zone, srcSel)
    val keepSnap = remember { WallMem.collectKeep.value?.takeIf { it.first == ck0 && it.second.seq.isNotEmpty() }?.second }
    var kw by remember { mutableStateOf(kw0) }
    var runSeq by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(keepSnap?.total ?: 0) }
    var done by remember { mutableIntStateOf(keepSnap?.done ?: 0) }
    var fails by remember { mutableIntStateOf(keepSnap?.fails ?: 0) }
    var tabIdx by remember { mutableStateOf(keepSnap?.tabIdx) } // null=全部
    // 用户 2026-11-06 指示（偏离 web 新增）→同日三态终改：搜索排序钮循环「年份↓(默认)→年份↑→默认顺序」。
    // 调研依据：aria-sort 三态范式 + 时间类数据默认倒序（jOOq Sensible Default Ordering）；钮示当前已应用态
    var yearSort by remember { mutableStateOf(keepSnap?.yearSort ?: YearOrder.DESC) }
    val bySite = remember { mutableStateMapOf<Int, List<VodSummary>>().also { m -> keepSnap?.let { m.putAll(it.bySite) } } }
    var seq by remember { mutableStateOf(keepSnap?.seq ?: emptyList()) } // 到达序
    var noSources by remember { mutableStateOf(keepSnap?.noSources ?: false) }

    BackHandler { onBack() }

    fun appendTab(ep: Int, si: Int, list: List<VodSummary>) {
        if (ep != runSeq || list.isEmpty()) return
        bySite[si] = list
        if (!seq.contains(si)) seq = seq + si
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { vocab.addWords(list.map { it.name }, zone) } // 终审 #4：调用点混跨 IO/Main，统一交后台派生
    }

    /** 单源一轮：成功写分片+上屏，失败计数入补试名单；返回是否成功 */
    suspend fun fire(ep: Int, ck: String, kwNow: String, x: Pair<Int, VodSource>, failed: MutableList<Pair<Int, VodSource>>, doneCnt: AtomicInteger): Boolean {
        val (si, s) = x
        val list = runCatching {
            kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                repo.searchSourcePage1(s, kwNow).filter { CmsParser.relevantForSearch(it.name, kwNow, settings.fuzzy) }
            }
        }.getOrNull()
        if (ep != runSeq) return list != null
        if (list == null) {
            synchronized(failed) { fails++; failed.add(x) }
        } else {
            val shards = shardsOf(ck)
            shards[PerfCore.norm(s.url)] = PerfCore.Shard(System.currentTimeMillis(), list)
            persistShards(ck, shards)
            appendTab(ep, si, list)
        }
        val d = doneCnt.incrementAndGet()
        done = d
        return list != null
    }

    fun start(ep: Int, kwNow: String) {
        scope.launch {
            val ck = PerfCore.searchKey(kwNow)
            // 用户 2026-11-06 走查 #4：取源表+番号全分片扫描+SWR 分片回灌（磁盘读+JSON 解码）下 IO；
            // 上屏（appendTab 写状态）回 Main 执行，波次派发不变
            val (items, codeHits, swrHits) = withContext(Dispatchers.IO) {
                val vodAll = store.vodSources()
                val all = vodAll.mapIndexed { si, s -> si to s }
                // 死源过滤仅在自动维护开启时生效（web collectSources 同款：关掉维护就全源照搜）
                var its = all.filter { it.second.isAdult == zone && !(settings.autoMaintain && health.isDead(it.second.url)) }
                // P0-1 坐标统一：siteIdx=点播池下标，排优先须换算为全表下标（专区池无当前源=-1 不排）
                val curFull = if (zone) -1 else SiteIdx.poolToFull(vodAll, settings.siteIdx)
                its = PerfCore.orderSources(its, curFull, { it.first }, { health.of(it.second.url)?.ms })
                if (srcSel != null && its.any { it.first == srcSel.si })
                    its = its.filter { it.first == srcSel.si } // 指定源生效；源已删则回退聚合
                if (its.isEmpty()) return@withContext Triple(its, emptyList<Pair<Int, List<VodSummary>>>(), emptyList())
                // 番号查询本地秒查（用户 2026-11-04）：扫聚合 latest 分片（每源 ≤800 条）直接过滤命中
                val code = CmsParser.codeCanon(kwNow)?.let { canon ->
                    its.mapNotNull { (si, s) ->
                        val hits = Agg.shards.getRaw(Agg.key(s, null))?.v
                            ?.filter { CmsParser.codeMatch(it.name, canon) }.orEmpty()
                        if (hits.isEmpty()) null else si to hits
                    }
                } ?: emptyList()
                // SWR：上轮分片（fresh+stale，含跨会话落盘回灌）
                val swr = shardsOf(ck).takeIf { it.isNotEmpty() }?.let { shards ->
                    val siOf = its.associateBy({ PerfCore.norm(it.second.url) }, { it })
                    val split = PerfCore.splitTtl(shards, CacheTiers.TIERS[settings.cacheTier].search)
                    (split.fresh.entries + split.stale.entries).mapNotNull { (u, list) ->
                        val x = siOf[u] ?: return@mapNotNull null
                        x.first to list
                    }
                } ?: emptyList()
                Triple(its, code, swr)
            }
            total = items.size
            done = 0; fails = 0
            bySite.clear(); seq = emptyList(); tabIdx = null; noSources = items.isEmpty()
            if (items.isEmpty()) return@launch
            // 命中即上屏；服务端搜索并行进行、到达后经 appendTab 整替（含最新池外的全库条目）
            codeHits.forEach { (si, hits) -> appendTab(ep, si, hits) }
            swrHits.forEach { (si, list) -> appendTab(ep, si, list) }

            val failed = mutableListOf<Pair<Int, VodSource>>()
            val doneCnt = AtomicInteger(0)
            var retried = false
            suspend fun runOne(x: Pair<Int, VodSource>) {
                fire(ep, ck, kwNow, x, failed, doneCnt)
                val d = doneCnt.get()
                // 全部返回且无任何命中：有失败源先补试一轮（8s 超时把「慢」误判成「无」），仍空才落空态
                if (ep == runSeq && tabIdx == null && d >= total && bySite.isEmpty()) {
                    if (!retried) {
                        val again = synchronized(failed) { if (failed.isEmpty()) null else failed.toList().also { failed.clear() } }
                        if (again != null) {
                            retried = true
                            fails = 0; done = total - again.size
                            doneCnt.set(total - again.size)
                            again.forEach { y -> scope.launch(Dispatchers.IO) { runOne(y) } }
                        }
                    }
                }
            }
            val (first, rest) = PerfCore.splitBatches(items, settings.searchThreads)
            first.forEach { x -> scope.launch(Dispatchers.IO) { runOne(x) } }
            if (rest.isNotEmpty()) scope.launch(Dispatchers.IO) {
                delay(1200)
                if (ep == runSeq) rest.forEach { x -> launch { runOne(x) } }
            }
        }
    }

    fun searchNow(w: String) {
        val t = w.trim(); if (t.isEmpty()) return
        kw = t
        vocab.pushKw(t, zone)
        val ep = runSeq + 1; runSeq = ep
        start(ep, t)
    }

    LaunchedEffect(Unit) { if (keepSnap == null) searchNow(kw0) } // 走查记忆：命中快照=还原上屏，不重搜

    // 结果墙快照回存：seq/条目/计数/Tab/排序任一变化即写 keep（空 seq 不写，避免重搜清屏瞬间覆盖旧墙）
    val ckNow = collectKey(kw, zone, srcSel)
    val sitesSig = bySite.values.sumOf { it.size }
    LaunchedEffect(ckNow, seq, sitesSig, tabIdx, yearSort, done, total, fails, noSources) {
        if (seq.isNotEmpty()) WallMem.collectKeep.value =
            ckNow to CollectSnap(seq, bySite.toMap(), tabIdx, yearSort, done, total, fails, noSources)
    }
    // 滚动记忆：右墙按 key#tab 分槽、左源栏按 key；首发瞬态跳过（同 browseScroll minor#6），250ms 节流
    val scr0 = remember { WallMem.collectScroll[ck0 + "#$tabIdx"] ?: (0 to 0) }
    val gridState = rememberLazyGridState(scr0.first, scr0.second)
    LaunchedEffect(ckNow) {
        var first = true
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect {
                if (first) { first = false; return@collect }
                WallMem.collectScroll[collectKey(kw, zone, srcSel) + "#$tabIdx"] = it
                delay(250)
            }
    }
    val srcScr0 = remember { WallMem.collectSrcScroll[ck0] ?: 0 }
    val srcState = rememberLazyListState(srcScr0)
    LaunchedEffect(ckNow) {
        var first = true
        snapshotFlow { srcState.firstVisibleItemIndex }
            .collect {
                if (first) { first = false; return@collect }
                WallMem.collectSrcScroll[collectKey(kw, zone, srcSel)] = it
                delay(250)
            }
    }

    val allItems = if (tabIdx != null) bySite[tabIdx] ?: emptyList()
    else seq.flatMap { bySite[it] ?: emptyList() }
    // 用户 2026-11-06：排序仅影响展示序，空态/chip 判断仍用原始 allItems
    val shown = when (yearSort) {
        YearOrder.DESC -> Agg.yearDesc(allItems)
        YearOrder.ASC -> Agg.yearAsc(allItems)
        YearOrder.NONE -> allItems
    }
    val allDone = total > 0 && done >= total
    // 用户 2026-11-06 走查 #8：联想词索引遍历（8000 词 suggest/fuzzy/truncateByQuery）原在组合期同步跑
    // → 挪 Default 线程，组合期只读状态；触发条件不变（全空态+全部返回完成+聚合 Tab 才计算）
    var suggChips by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(kw, zone, allDone, tabIdx, allItems.isEmpty()) {
        suggChips = if (allItems.isNotEmpty() || !allDone || tabIdx != null) emptyList()
        else {
            val q = kw.lowercase(Locale.ROOT)
            if (!Regex("^[a-z0-9]{2,}$").matches(q)) emptyList()
            else withContext(Dispatchers.Default) {
                val pi = vocab.index(zone)
                val hits = pi.suggest(q, 30)
                val cand = (hits + pi.fuzzy(q, 30)).distinct()
                val truncs = PerfCore.truncateByQuery(q, cand, 2, 2) { pi.variants(it) }
                if (truncs.isNotEmpty()) truncs else hits.take(2)
            }
        }
    }
    val poolSources = remember { store.vodSources() }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 统一 TmBtn 紧凑小号（用户 2026-11-05）
            TmBtn(onClick = onBack) { Text("← 返回") }
            Text("“$kw” 的搜索结果", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            // 方案B（用户 2026-11-06）：重搜钮=同词整页重跑+回顶（返回还原之外，明确刷新入口）
            TmBtn(onClick = {
                scope.launch {
                    gridState.scrollToItem(0)
                    srcState.scrollToItem(0)
                }
                searchNow(kw)
            }) { Text("重搜") }
            YearSortBtn(order = yearSort) {
                yearSort = YearOrder.entries[(yearSort.ordinal + 1) % YearOrder.entries.size]
            }
            Text(
                if (noSources) "无点播源" else "已返回 $done/$total · 失败 $fails",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // 用户 2026-11-03（TV 布局参考图，偏离 web 顶部横排 Tab）：左=源栏垂直列表，右=海报墙，遥控左右移焦直达
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (seq.isNotEmpty()) {
                val allCount = seq.sumOf { bySite[it]?.size ?: 0 }
                LazyColumn(
                    state = srcState,
                    modifier = Modifier.width(200.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        SourceColRow(label = "全部($allCount)", selected = tabIdx == null, onClick = { tabIdx = null })
                    }
                    itemsIndexed(seq) { _, si ->
                        val sName = poolSources.getOrNull(si)?.name ?: "源$si"
                        SourceColRow(
                            label = "$sName(${bySite[si]?.size ?: 0})",
                            selected = tabIdx == si,
                            onClick = { tabIdx = si },
                        )
                    }
                }
            }
            if (allItems.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            noSources -> "无点播源：请先到「源管理」导入"
                            tabIdx != null -> "该源暂无数据"
                            !allDone -> "等待各源返回…"
                            fails > 0 -> "各源无命中（$fails 个源失败/超时，可返回重搜）"
                            else -> "各源均无结果：可换首字母简拼、直接输入中文名或换源重试"
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    if (suggChips.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggChips) { w ->
                                TmChip(selected = false, onClick = { searchNow(w) }, label = "试试搜「$w」?")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 4K 投影崩溃修复（用户 2026-11-11）：撤 key=sourceUrl|vodId（池内重复，大 dp 全挂载即崩）
                    gridItems(shown, contentType = { "poster" }) { it ->
                        PosterCard(
                            item = it, onClick = { onPick(it) },
                            // TVBox 对标 C2（2026-11-10）：源名前缀受「数据源显示」开关控制
                            siteLabel = if (tabIdx == null && settings.showSourceName) it.sourceName else null,
                            codeBadge = true,
                        )
                    }
                }
            }
        }
    }
}

/** 三态排序钮（用户 2026-11-06）：大箭头(18sp)+当前态文字明示已应用排序，替代 TmChip labelMedium
 *  截断「年份↑」看不清的问题；排序生效态(DESC/ASC)高亮白40%底，默认顺序态白20%底 */
@Composable
private fun YearSortBtn(order: YearOrder, onClick: () -> Unit) {
    val (icon, label) = when (order) {
        YearOrder.DESC -> "▼" to "年份↓ 最新在前"
        YearOrder.ASC -> "▲" to "年份↑ 最早在前"
        YearOrder.NONE -> "≡" to "默认顺序"
    }
    // 用户 2026-11-09 #133：遥控焦点=白描边+40%红底（全 app 统一）
    var sortF by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        modifier = Modifier.onFocusEvent { sortF = it.isFocused }
            .then(if (sortF) Modifier.border(com.tzk1986.libretvaz.ui.theme.TvColors.StrokeW, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (sortF) com.tzk1986.libretvaz.ui.theme.TvColors.Red40 else if (order == YearOrder.NONE) Color(0x33FFFFFF) else Color(0x66FFFFFF))
            .clickable(onClick = onClick)
            // C30（审计 2026-11-12）：与同排 TmBtn 32dp 基准等高（原 36dp 混排行高跳档；
            // 18sp 箭头行 24+内衬 6=36，压内衬至 3 再 heightIn 兜底 32）
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(icon, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** 左源栏行：选中高亮胶囊，整行可点，遥控上下连续移焦（用户 2026-11-03 两栏布局） */
@Composable
private fun SourceColRow(label: String, selected: Boolean, onClick: () -> Unit) {
    // 用户 2026-11-09 #133：左源栏行同获焦点白框；选中底色撤 secondaryContainer 紫改红 40%（TVBox 统一）
    var rowF by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusEvent { rowF = it.isFocused }
            .then(if (rowF) Modifier.border(com.tzk1986.libretvaz.ui.theme.TvColors.StrokeW, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (rowF || selected) com.tzk1986.libretvaz.ui.theme.TvColors.Red40 else Color.Transparent)
            .clickable(onClick = onClick)
            // C33（审计 2026-11-11 #33）：左源栏整宽单控件行（非行内混排，不受 32dp 等高约束），
            // vertical 9→14 使命中 20 行高+28=48dp
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Color.White
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
