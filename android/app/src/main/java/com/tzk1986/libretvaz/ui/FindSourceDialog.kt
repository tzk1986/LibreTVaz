package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 搜关联弹窗（对应 web openFindBox）：片名打散为候选词 chips（relCandidates：核心词→前缀短语→核心+季部→原名），
 * 默认按原名自动全源搜索＝旧「搜本作」，点候选词即换词重搜；点结果跨源跳转续播。Detail/Player 两处复用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindSourceDialog(
    store: SourceStore,
    title: String,
    onDismiss: () -> Unit,
    onPick: (VodSummary) -> Unit,
) {
    val repo = remember { CmsRepository() }
    val scope = rememberCoroutineScope()
    val candidates = remember(title) { PerfCore.relCandidates(title) }
    var kw by remember { mutableStateOf(title) }
    var results by remember { mutableStateOf<List<VodSummary>?>(null) }
    var searching by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun runFind(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        job?.cancel() // 迟到结果丢弃（对齐 web runToken epoch）
        job = scope.launch {
            searching = true
            results = null
            hint = null
            val sources = store.vodSources()
            if (sources.isEmpty()) {
                hint = "尚未添加点播源"
                results = emptyList()
                searching = false
                return@launch
            }
            val outcome = repo.search(query, sources)
            results = outcome.list.take(30)
            hint = if (results!!.isEmpty()) "各源均无结果" else null
            searching = false
        }
    }

    LaunchedEffect(Unit) { runFind(title) }

    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = {
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
                    label = { Text("片名/关联词可编辑，回车前点「全源搜索」") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
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
            when {
                searching -> Text("全源搜索「$kw」中…")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    hint?.let { item { Text(it) } }
                    items(results!!) { it2 ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(it2) }.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(it2.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            it2.remarks?.let { r -> Text(r, style = MaterialTheme.typography.bodySmall) }
                            Text(it2.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    })
}
