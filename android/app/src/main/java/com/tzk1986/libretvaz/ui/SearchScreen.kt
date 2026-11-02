package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.data.perf.VocabSeed
import kotlinx.coroutines.launch

/** 六列矩阵键盘：A-Z+0-9 共 36 键 6×6（web renderSearch KEYS 等价） */
private val KB_KEYS = ('A'..'Z').toSet() + ('0'..'9').toSet()
private val KB_ROWS = KB_KEYS.toList().chunked(6)

/**
 * 搜索输入页三栏（web renderSearch 等价，点播/资源口两池同构、数据全隔离）：
 * 左=本池搜索历史（竖排+清空）｜中=输入框+指定源+按钮组+36 键矩阵键盘｜
 * 右=单入口词流：空输入=热门搜索 top(40)，有输入=关联词（suggest→fuzzy 补足→截断词置顶带标）。
 * 发起搜索经 onCollect 跳结果页；远程输入钮暂留提示（B8 接扫码）。
 */
@Composable
fun SearchScreen(
    store: SourceStore,
    zone: Boolean,
    onBack: () -> Unit,
    onCollect: (kw: String, srcSel: SpecSrc?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { CmsRepository() }
    val health = remember { HealthStore(context) }
    val vocab = remember { VocabPools.get(context) }

    var query by remember { mutableStateOf("") }
    var srcSel by remember { mutableStateOf<SpecSrc?>(null) }
    var showSrcPick by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var kws by remember { mutableStateOf(vocab.kws(zone)) }
    val focus = remember { FocusRequester() }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // 进页词库种子（web seedVocab/seedZoneVocab 等价：本池健康源目录剧名，6h 节流，达标早停）
    LaunchedEffect(zone) {
        val min = if (zone) 1500 else 3000
        val pi = vocab.index(zone)
        if (pi.size() >= min) return@LaunchedEffect
        val pool = store.vodSources().filter { it.isAdult == zone && !health.isDead(it.url) }
            .sortedBy { health.of(it.url)?.ms ?: 4000L }
        val byUrl = pool.associateBy { it.url }
        val t = VocabSeed.run(
            sources = pool.map { it.url }.take(VocabSeed.MAX_SOURCES),
            fetchPage = { u, pg ->
                val src = byUrl[u] ?: return@run emptyList()
                runCatching { repo.home(src, pg).map { it.name } }.getOrDefault(emptyList())
            },
            index = pi,
            min = min,
            lastSeedAt = vocab.seedAt(zone),
        )
        if (t != null) vocab.setSeedAt(t, zone)
        kws = vocab.kws(zone) // 触底重绘词流
    }

    fun search(w: String) {
        val t = w.trim()
        if (t.isEmpty()) { hint = "请先输入或点选关键词"; return }
        hint = null
        vocab.pushKw(t, zone)
        kws = vocab.kws(zone)
        onCollect(t, srcSel)
    }

    fun pressKey(c: String) {
        query = when (c) {
            "⌫" -> query.dropLast(1)
            "CLR" -> ""
            else -> query + c.lowercase()
        }
    }

    // 右列单入口词流：空=热榜，有输入=关联词（suggest→fuzzy→截断置顶带标）
    val q = query.trim().lowercase()
    val pi = vocab.index(zone)
    val rightWords: List<Pair<String, Boolean>> = remember(q, pi.size(), zone) {
        if (q.isEmpty()) pi.top(40).map { it to false }
        else {
            val alphaNum = Regex("^[a-z0-9]+$").matches(q)
            var hits = if (alphaNum) pi.suggest(q, 40) else emptyList()
            if (hits.isEmpty()) hits = pi.list().filter { it.lowercase().contains(q) }
            else {
                val seen = hits.toHashSet()
                hits = hits + pi.fuzzy(q, (40 - hits.size).coerceAtLeast(0)).filter { it !in seen }
            }
            hits = hits.distinct()
            val truncs = if (alphaNum) PerfCore.truncateByQuery(q, hits, 3, 2) { pi.variants(it) } else emptyList()
            val marked = truncs.toHashSet()
            (truncs + hits.filter { it !in marked }).take(40).map { it to (it in marked) }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onBack) { Text("← ${if (zone) "资源口" else "点播"}") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入或点选键盘，回车搜索（${if (zone) "资源口" else "点播"}池）", maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Button(onClick = { search(query) }) { Text("🔍 搜索") }
        }

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 左列：本池搜索历史
            Column(modifier = Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { vocab.clearKw(zone); kws = emptyList() }) { Text("清空") }
                }
                if (kws.isEmpty()) Text("暂无本池搜索历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(kws) { k ->
                        AssistChip(onClick = { search(k) }, label = { Text(k, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            // 中列：指定源 + 按钮组 + 矩阵键盘
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showSrcPick = true }, modifier = Modifier.weight(1f)) {
                        Text(srcSel?.let { "📌 ${it.name}" } ?: "全部源（聚合）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { showSrcPick = true }) { Text("指定源") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { search(query) }) { Text("🔍 搜索") }
                    OutlinedButton(onClick = { pressKey("⌫") }) { Text("⌫ 退格") }
                    OutlinedButton(onClick = { pressKey("CLR") }) { Text("清空输入") }
                    OutlinedButton(onClick = { hint = "远程扫码输入将在服务器配对模式提供" }) { Text("📱 远程输入") }
                }
                KB_ROWS.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            FilledTonalButton(
                                onClick = { pressKey(c.toString()) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text(c.toString()) }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "依次点选片名拼音首字母（如「庆余年」按 QYN）或用数字键，右侧实时联想本池片名（空输入时为热门词）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            // 右列：热门搜索/关联词 单入口词流
            Column(modifier = Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (q.isEmpty()) "热门搜索" else "关联词", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vocab.clearKw(zone); kws = emptyList() }) { Text("删除历史") }
                }
                if (rightWords.isEmpty()) {
                    Text(
                        if (q.isEmpty()) "词库建设中…（搜索/进墙自动积累本池片名）" else "没有关联词，清空输入可看热榜，或换字母试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(rightWords) { i, (w, trunc) ->
                        AssistChip(
                            onClick = { query = w; search(w) },
                            label = {
                                Text(
                                    (if (i < 3 && q.isEmpty()) "🔥 " else "") + w + (if (trunc) " ·截断" else ""),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSrcPick) {
        val all = store.vodSources().mapIndexed { si, s -> si to s }.filter { it.second.isAdult == zone }
        AlertDialog(
            onDismissRequest = { showSrcPick = false },
            confirmButton = { TextButton(onClick = { showSrcPick = false }) { Text("关闭") } },
            title = { Text("指定搜索源（${if (zone) "资源口" else "点播"}）") },
            text = {
                LazyColumn {
                    item {
                        SrcPickRow(
                            name = "全部源（聚合）",
                            selected = srcSel == null,
                            onClick = { srcSel = null; showSrcPick = false },
                        )
                    }
                    items(all) { (si, s) ->
                        SrcPickRow(
                            name = s.name,
                            selected = srcSel?.si == si,
                            onClick = { srcSel = SpecSrc(si, s.name); showSrcPick = false },
                        )
                    }
                    item {
                        Text("选定后本次仅搜该源；结果页仍按源分 Tab（只有一列）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                }
            },
        )
    }
}

@Composable
private fun SrcPickRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        else Button(onClick = onClick) { Text("选择") }
    }
}
