package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CacheTiers
import com.tzk1986.libretvaz.data.CmsParser
import com.tzk1986.libretvaz.data.CmsRepository
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 搜关联弹窗（对应 web openFindBox）：片名打散为候选词 chips（relCandidates：核心词→前缀短语→核心+季部→原名），
 * 默认按原名自动全源搜索＝旧「搜本作」，点候选词即换词重搜；点结果跨源跳转续播。Detail/Player 两处复用。
 * zone=true 走专区池路由（web collectSources(zone)，资源口「🔎 找片」入口）；空 title 不自动搜，等手输触发。
 * matchBest=true＝换源弹框（用户 2026-11-03）：每源仅取严格同名一条（等值→包含），增量上屏+逐行测速（zsp 7d/detail 6h
 * 磁盘缓存复用 Zone 管线），不写搜索分片避免污染聚合结果；excludeUrl 排除当前源。BACK 由 Dialog 自身消费关闭回上一页。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val candidates = remember(title) { if (matchBest) emptyList() else PerfCore.relCandidates(title) }
    var kw by remember { mutableStateOf(title) }
    var results by remember { mutableStateOf<List<VodSummary>?>(null) }
    var searching by remember { mutableStateOf(title.isNotBlank()) } // 空词（专区找片入口）不自动搜，手输后点钮触发
    var hint by remember { mutableStateOf<String?>(null) }
    var runToken by remember { mutableIntStateOf(0) }
    val found = sortedMapOf<Int, List<VodSummary>>() // si→该源命中（web bySi：整数值键自动按源序展平）

    // 换源测速：行「源|id」→起播速度角标（并发 3，7d 缓存秒回）；probed 会话去重防增量重发
    val rowTags = remember { mutableStateMapOf<String, PerfCore.SpeedTag>() }
    val probed = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    val probeSem = remember { kotlinx.coroutines.sync.Semaphore(3) }
    fun rowKey(it2: VodSummary) = "${PerfCore.norm(it2.sourceUrl)}|${it2.vodId}"
    fun probe(it2: VodSummary) {
        if (!matchBest || !settings.lineSpeed) return
        val k = rowKey(it2)
        if (!probed.add(k)) return
        ZSP.get("zsp:$k", ZSP_TTL)?.let { kb ->
            PerfCore.lineSpeedTag(kb)?.let { rowTags[k] = it }
            return
        }
        scope.launch(Dispatchers.IO) {
            val kb = runCatching {
                probeSem.withPermit {
                    val src = VodSource(it2.sourceName, it2.sourceUrl, isAdult = zone)
                    val d = ZDETAIL.get("detail:$k", ZDETAIL_TTL) ?: repo.detail(src, it2.vodId)
                    d?.let { ZDETAIL.set("detail:$k", it) }
                    val link = d?.lines?.firstOrNull()?.list?.firstOrNull()?.link
                    if (link == null) 0L else LineSpeed.sampleKbps(LineSpeed.segOrSelf(link))
                }
            }.getOrDefault(-1L)
            if (kb >= 0) ZSP.set("zsp:$k", kb)
            PerfCore.lineSpeedTag(kb)?.let { rowTags[k] = it }
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
            val items = store.vodSources().mapIndexed { si, s -> si to s }.filter {
                it.second.isAdult == zone && (exN == null || PerfCore.norm(it.second.url) != exN)
            } // web collectSources(zone)；换源模式排除当前源
            if (items.isEmpty()) {
                hint = if (zone) "专区池暂无源" else "尚未添加点播源"
                results = emptyList(); searching = false; return@launch
            }
            synchronized(found) { found.clear() }
            val ck = PerfCore.searchKey(query)
            val siOf = items.associateBy({ PerfCore.norm(it.second.url) }, { it })
            val split = PerfCore.splitTtl(shardsOf(ck), CacheTiers.TIERS[settings.cacheTier].search)
            val nt0 = CmsParser.normalizeTitle(query)
            (split.fresh.entries + split.stale.entries).forEach { (u, list) ->
                val x = siOf[u] ?: return@forEach
                val l = if (matchBest) {
                    listOfNotNull(
                        list.firstOrNull { CmsParser.normalizeTitle(it.name) == nt0 }
                            ?: list.firstOrNull { CmsParser.normalizeTitle(it.name).contains(nt0) },
                    )
                } else list
                if (l.isNotEmpty()) synchronized(found) { found[x.first] = l }
            }
            fun flush() {
                if (ep != runToken) return
                results = synchronized(found) { found.values.flatten() }.take(30)
                results?.forEach { probe(it) } // 换源模式：新到行即起测（probed 去重，7d 缓存秒回）
            }
            flush()
            suspend fun fire(x: Pair<Int, VodSource>) {
                val nt = CmsParser.normalizeTitle(query)
                val raw = runCatching {
                    withTimeout(8_000L) { repo.searchSourcePage1(x.second, query) }
                }.getOrNull() ?: return
                // 换源模式：每源仅一条严格同名（等值→包含兜底），且不落搜索分片（防污染聚合/SWR）；搜关联维持相关性过滤
                val list = if (matchBest) {
                    listOfNotNull(
                        raw.firstOrNull { CmsParser.normalizeTitle(it.name) == nt }
                            ?: raw.firstOrNull { CmsParser.normalizeTitle(it.name).contains(nt) },
                    )
                } else raw.filter { CmsParser.relevantForSearch(it.name, query, settings.fuzzy) }
                if (!matchBest) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        // 用户 2026-11-03：加宽弹框。平台默认 wrap 宽（usePlatformDefaultWidth）会钳掉 widthIn，必须先放开
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.86f).padding(horizontal = 16.dp),
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = kw,
                    onValueChange = { kw = it },
                    label = { Text("片名/关联词可编辑，回车即全源搜索") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runFind(kw) }),
                    // K4：TV 遥控无软键盘 imeAction，DPAD Enter 直接触发搜索
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.DirectionCenter)) {
                                runFind(kw.trim()); true
                            } else false
                        },
                )
                Button(onClick = { runFind(kw) }) { Text("全源搜索") }
            }
            // 候选词词流＝web relRow：点选即换词重搜，选中高亮
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { c ->
                    FilterChip(
                        selected = c == kw.trim(),
                        onClick = { kw = c; runFind(c) },
                        label = { Text(c, maxLines = 1) },
                    )
                }
            }
            // 参考图（用户 2026-11-03）：行＝📹 源名 | 名称 | 类型/年份/完结徽标；换源模式行尾附 ⚡测速
            val r = results
            if (matchBest) Text(
                if (searching) "正在逐源查找同名可换…" else "点击任一行直接换源续播（⚡为该源首线首集起播速度）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            when {
                r == null && searching -> Text("全源搜索「$kw」中…")
                r == null -> Text("输入或点选片名后按「全源搜索」", style = MaterialTheme.typography.bodySmall)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (searching) item { Text("陆续返回中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    hint?.let { item { Text(it) } }
                    items(r) { it2 ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(it2) }.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📹", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                it2.sourceName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp),
                            )
                            Text(it2.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            listOfNotNull(it2.typeName?.ifBlank { null }, it2.year?.ifBlank { null }, it2.remarks?.ifBlank { null })
                                .joinToString(" ").takeIf { s -> s.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1)
                                }
                            if (matchBest) {
                                val t = rowTags[rowKey(it2)]
                                Text(
                                    t?.label ?: "⚡测速中",
                                    color = t?.let { speedTagColor(it.cls) } ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    })
}
