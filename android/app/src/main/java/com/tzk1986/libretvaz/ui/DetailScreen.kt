package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.*
import kotlinx.coroutines.launch

/**
 * 详情页（对应 web openDetail）：多线路 FilterChip / 命名选集网格（默认线优先取带流后缀线路）/
 * 简介 / 搜本作换源直达播放。线路与集以 onPlay 携带上下文交给播放页（对应 web openDetail 闭包）。
 */
@Composable
fun DetailScreen(
    item: VodSummary,
    onBack: () -> Unit,
    onPlay: (item: VodSummary, source: VodSource, detail: VideoDetail, startLine: Int, startEp: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CmsRepository() }
    val store = remember { SourceStore(context) }
    val source = remember(item) { VodSource(item.sourceName, item.sourceUrl, isAdult = item.isAdult) }
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf(0) }
    var finding by remember { mutableStateOf(false) }
    var switchHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item) {
        detail = null
        failed = false
        switchHint = null
        val d = repo.detail(source, item.vodId)
        if (d == null || d.lines.isEmpty()) {
            failed = true
        } else {
            detail = d
            line = Playback.pickDefaultLine(d.lines).coerceAtLeast(0)
        }
    }

    BackHandler { onBack() }

    val d = detail
    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onBack) { Text("← 返回") }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdultTag(adultOf(item))
                    Text(item.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    listOfNotNull(item.year, item.area, item.typeName, "来源：${item.sourceName}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = { finding = true }) { Text("🔎 搜本作") }
        }
        switchHint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        when {
            failed -> Text(
                "获取播放地址失败：该源接口异常，可点「搜本作」换源观看",
                color = MaterialTheme.colorScheme.error,
            )
            d == null -> Text("正在获取选集…")
            else -> {
                if (d.lines.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        d.lines.forEachIndexed { i, lg ->
                            FilterChip(
                                selected = i == line,
                                onClick = { line = i },
                                label = { Text("${lg.flag}（${lg.list.size}集）") },
                            )
                        }
                    }
                }
                val g = d.lines[line]
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 128.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    val descText = d.desc?.replace(Regex("<[^>]+>"), " ")?.trim()
                    if (!descText.isNullOrBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (descText.length > 300) descText.take(300) + "…" else descText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            )
                        }
                    }
                    itemsIndexed(g.list) { i, e ->
                        Button(onClick = { onPlay(item, source, d, line, i) }, modifier = Modifier.fillMaxWidth()) {
                            Text(e.name, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (finding) FindSourceDialog(
        store = store,
        title = d?.title?.ifBlank { null } ?: item.name,
        onDismiss = { finding = false },
        onPick = { sum ->
            finding = false
            val src = VodSource(sum.sourceName, sum.sourceUrl, isAdult = sum.isAdult)
            scope.launch {
                val d2 = repo.detail(src, sum.vodId)
                if (d2 == null || d2.lines.isEmpty()) {
                    switchHint = "「${sum.sourceName}」未返回播放地址，请再换一个源"
                    return@launch
                }
                onPlay(sum, src, d2, Playback.pickDefaultLine(d2.lines).coerceAtLeast(0), 0)
            }
        },
    )
}
