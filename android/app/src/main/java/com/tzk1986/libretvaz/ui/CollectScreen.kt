package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 指定搜索源：全表下标+源名（null=全部源聚合，web Search.srcSel 等价） */
data class SpecSrc(val si: Int, val name: String)

/** 搜索分片缓存：ck → (norm(url) → 带时间戳分片)，内存+DiskCache 双层（web searchShardSet/localStorage 等价，跨会话 SWR）；找片框共用同缓存（web openFindBox 同款 searchShardSet） */
internal val SEARCH_CACHE = ConcurrentHashMap<String, ConcurrentHashMap<String, PerfCore.Shard<VodSummary>>>()

/** 设置页「清理缓存」钩子（web cache_purge 等价：进程内存 + 磁盘分片一起清） */
internal fun clearSearchShardsCache() { SEARCH_CACHE.clear(); DiskCache.removePrefix("search:") }

/** 读盘回灌：内存未中时按 ck 反序列化 DiskCache 分片（写失败/坏数据=空表） */
internal fun shardsOf(ck: String): ConcurrentHashMap<String, PerfCore.Shard<VodSummary>> =
    SEARCH_CACHE.computeIfAbsent(ck) { ConcurrentHashMap() }.apply {
        if (isEmpty()) {
            runCatching {
                val (_, payload) = DiskCache.read(ck) ?: return@runCatching
                val o = org.json.JSONObject(payload)
                for (u in o.keys()) {
                    val e = o.optJSONObject(u) ?: continue
                    val list = CmsCodecs.decSummaries(e.getJSONArray("v").toString()) ?: continue
                    put(u, PerfCore.Shard(e.getLong("t"), list))
                }
            }
        }
    }

/** 分片整包落盘（内存 map 变更后异步写 DiskCache，7 天硬清兜底） */
internal fun persistShards(ck: String, map: Map<String, PerfCore.Shard<VodSummary>>) {
    runCatching {
        val o = org.json.JSONObject()
        map.forEach { (u, sh) ->
            o.put(u, org.json.JSONObject().put("t", sh.t).put("v", org.json.JSONArray(CmsCodecs.encSummaries(sh.d))))
        }
        DiskCache.write(ck, System.currentTimeMillis(), o.toString())
    }
}

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

    var kw by remember { mutableStateOf(kw0) }
    var runSeq by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var fails by remember { mutableIntStateOf(0) }
    var tabIdx by remember { mutableStateOf<Int?>(null) } // null=全部
    val bySite = remember { mutableStateMapOf<Int, List<VodSummary>>() }
    var seq by remember { mutableStateOf<List<Int>>(emptyList()) } // 到达序
    var noSources by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun appendTab(ep: Int, si: Int, list: List<VodSummary>) {
        if (ep != runSeq || list.isEmpty()) return
        bySite[si] = list
        if (!seq.contains(si)) seq = seq + si
        vocab.addWords(list.map { it.name }, zone)
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
            val all = store.vodSources().mapIndexed { si, s -> si to s }
            // 死源过滤仅在自动维护开启时生效（web collectSources 同款：关掉维护就全源照搜）
            var items = all.filter { it.second.isAdult == zone && !(settings.autoMaintain && health.isDead(it.second.url)) }
            items = PerfCore.orderSources(items, settings.siteIdx, { it.first }, { health.of(it.second.url)?.ms })
            if (srcSel != null && items.any { it.first == srcSel.si })
                items = items.filter { it.first == srcSel.si } // 指定源生效；源已删则回退聚合
            total = items.size
            done = 0; fails = 0
            bySite.clear(); seq = emptyList(); tabIdx = null; noSources = items.isEmpty()
            if (items.isEmpty()) return@launch

            // 番号查询本地秒查（用户 2026-11-04）：扫聚合 latest 分片（每源 ≤800 条）直接过滤命中即上屏，
            // 服务端搜索并行进行、到达后经 appendTab 整替（含最新池外的全库条目）
            CmsParser.codeCanon(kwNow)?.let { canon ->
                items.forEach { (si, s) ->
                    val hits = Agg.shards.getRaw(Agg.key(s, null))?.v
                        ?.filter { CmsParser.codeMatch(it.name, canon) }.orEmpty()
                    if (hits.isNotEmpty()) appendTab(ep, si, hits)
                }
            }

            // SWR：上轮分片（fresh+stale，含跨会话落盘回灌）先上屏，联网结果随后替换
            val ck = PerfCore.searchKey(kwNow)
            shardsOf(ck).takeIf { it.isNotEmpty() }?.let { shards ->
                val siOf = items.associateBy({ PerfCore.norm(it.second.url) }, { it })
                val split = PerfCore.splitTtl(shards, CacheTiers.TIERS[settings.cacheTier].search)
                (split.fresh.entries + split.stale.entries).forEach { (u, list) ->
                    val x = siOf[u] ?: return@forEach
                    appendTab(ep, x.first, list)
                }
            }

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

    LaunchedEffect(Unit) { searchNow(kw0) }

    val allItems = if (tabIdx != null) bySite[tabIdx] ?: emptyList()
    else seq.flatMap { bySite[it] ?: emptyList() }
    val allDone = total > 0 && done >= total
    val suggChips: List<String> = if (allItems.isNotEmpty() || !allDone || tabIdx != null) emptyList()
    else {
        val q = kw.lowercase(Locale.ROOT)
        if (!Regex("^[a-z0-9]{2,}$").matches(q)) emptyList()
        else {
            val pi = vocab.index(zone)
            val hits = pi.suggest(q, 30)
            val cand = (hits + pi.fuzzy(q, 30)).distinct()
            val truncs = PerfCore.truncateByQuery(q, cand, 2, 2) { pi.variants(it) }
            if (truncs.isNotEmpty()) truncs else hits.take(2)
        }
    }
    val poolSources = remember { store.vodSources() }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 返回钮缩小为文字级（用户 2026-11-04：与左源栏切换按钮同量级，Button 实心块太大）
            TextButton(onClick = onBack) { Text("← 返回", style = MaterialTheme.typography.bodyMedium) }
            Text("“$kw” 的搜索结果", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
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
                                AssistChip(onClick = { searchNow(w) }, label = { Text("试试搜「$w」?") })
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(allItems, key = { "${it.sourceUrl}|${it.vodId}" }) { it ->
                        PosterCard(
                            item = it, onClick = { onPick(it) },
                            siteLabel = if (tabIdx == null) it.sourceName else null,
                            codeBadge = true,
                        )
                    }
                }
            }
        }
    }
}

/** 左源栏行：选中高亮胶囊，整行可点，遥控上下连续移焦（用户 2026-11-03 两栏布局） */
@Composable
private fun SourceColRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
