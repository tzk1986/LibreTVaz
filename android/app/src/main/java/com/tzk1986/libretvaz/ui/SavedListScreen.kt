package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HistStore
import com.tzk1986.libretvaz.data.KeepChk
import com.tzk1986.libretvaz.data.KeepStore
import com.tzk1986.libretvaz.data.SavedItem
import com.tzk1986.libretvaz.data.VodSummary

/** 收藏/历史通用列表页（web renderKeep/renderHist 的 Kotlin 等价）：本池条目倒序，行删除，历史可清空 */
@Composable
fun SavedListScreen(
    title: String,
    items: List<SavedItem>,
    emptyText: String,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    onRemove: (VodSummary) -> Unit,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", style = MaterialTheme.typography.bodyMedium) }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (onClear != null && items.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("清空") }
            }
        }
        if (items.isEmpty()) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(items, key = { "${it.vod.sourceUrl}|${it.vod.vodId}" }) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(s.vod) }.padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AdultTag(s.vod.isAdult)
                            Text(s.vod.name, style = MaterialTheme.typography.bodyLarge)
                            if (s.updated) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "已更新",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(
                            listOfNotNull(s.vod.year, s.vod.area, s.vod.remarks).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Text(s.vod.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { onRemove(s.vod) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
fun KeepScreen(zone: Boolean, onBack: () -> Unit, onPick: (VodSummary) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { KeepStore(context) }
    val repo = remember { CmsRepository() }
    var items by remember { mutableStateOf(store.list(zone)) }
    // web checkKeeps：进页触发比对（30min 门控在引擎内），有变化即刷新
    LaunchedEffect(zone) {
        if (KeepChk.run(repo, store, zone)) items = store.list(zone)
    }
    SavedListScreen(
        title = if (zone) "专区收藏" else "收藏",
        items = items,
        emptyText = if (zone) "暂无专区收藏，去资源口收藏作品后在此查看" else "暂无收藏，在详情页点「收藏」加入此处",
        onBack = onBack,
        onPick = { store.clearUpdated(it); onPick(it) },
        onRemove = { store.remove(it); items = store.list(zone) },
        modifier = modifier,
    )
}

@Composable
fun HistScreen(zone: Boolean, onBack: () -> Unit, onPick: (VodSummary) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { HistStore(context) }
    var items by remember { mutableStateOf(store.list(zone)) }
    SavedListScreen(
        title = if (zone) "专区历史" else "历史",
        items = items,
        emptyText = if (zone) "暂无专区播放记录" else "暂无播放历史，观看后自动记录在此",
        onBack = onBack,
        onPick = onPick,
        onRemove = { store.remove(it); items = store.list(zone) },
        onClear = { store.clear(zone); items = store.list(zone) },
        modifier = modifier,
    )
}
