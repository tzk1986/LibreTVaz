package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSummary

/**
 * 搜本作弹窗（对应 web openFindBox）：以片名全源搜索，点结果跨源跳转续播。
 * Detail/Player 两处复用。
 */
@Composable
fun FindSourceDialog(
    store: SourceStore,
    title: String,
    onDismiss: () -> Unit,
    onPick: (VodSummary) -> Unit,
) {
    val repo = remember { CmsRepository() }
    var results by remember { mutableStateOf<List<VodSummary>?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(title) {
        val sources = store.vodSources()
        if (sources.isEmpty()) {
            hint = "尚未添加点播源"
            results = emptyList()
            return@LaunchedEffect
        }
        val outcome = repo.search(title, sources)
        results = outcome.list.take(30)
        hint = if (results!!.isEmpty()) "各源均无结果" else null
    }

    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = {
        TextButton(onClick = onDismiss) { Text("关闭") }
    }, text = {
        when {
            results == null -> Text("全源搜索「$title」中…")
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
    })
}
