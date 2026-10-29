package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.LiveSource
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSource
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(store: SourceStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var vodList by remember { mutableStateOf(store.vodSources()) }
    var liveList by remember { mutableStateOf(store.liveSources()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        vodList = store.vodSources()
        liveList = store.liveSources()
    }

    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Text("源管理", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }

        // 导入订阅链接（TVBox 配置 / LibreTV SourceList / 接口目录）
        Text("订阅导入（自动识别 TVBox / SourceList / 接口目录格式）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = importUrl,
                onValueChange = { importUrl = it },
                label = { Text("配置链接") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        message = runCatching {
                            val r = store.importFromUrl(importUrl)
                            refresh()
                            "已导入（${r.format}）：点播 +${r.vodAdded} · 直播 +${r.liveAdded}" +
                                (if (r.repos > 0) " · 仓库 ${r.repos - r.reposFailed}/${r.repos}" else "") +
                                if (r.skipped > 0) "，跳过 ${r.skipped} 个不支持条目（Spider/XML 等）" else ""
                        }.getOrElse { e -> "导入失败：${e.message}" }
                        busy = false
                    }
                }
            ) { Text(if (busy) "导入中…" else "导入") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        // 手动添加单个源
        Text("手动添加", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.weight(0.3f))
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("接口/播放列表地址") }, singleLine = true, modifier = Modifier.weight(0.7f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (store.addVod(VodSource(name, url))) { name = ""; url = ""; refresh(); message = "已添加点播源" }
                else message = "未添加：地址无效或已存在"
            }) { Text("＋ 点播源") }
            TextButton(onClick = {
                if (store.addLive(LiveSource(name, url))) { name = ""; url = ""; refresh(); message = "已添加直播源" }
                else message = "未添加：地址无效或已存在"
            }) { Text("＋ 直播源") }
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("点播源（${vodList.size}）", style = MaterialTheme.typography.titleMedium) }
            if (vodList.isEmpty()) {
                item { Text("（空）", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
            } else {
                items(vodList.mapIndexed { i, s -> i to s }) { (index, s) ->
                    SourceRow(s.name, s.url, s.isAdult) { store.removeVodAt(index); refresh() }
                }
            }
            item { Text("直播源（${liveList.size}）", style = MaterialTheme.typography.titleMedium) }
            if (liveList.isEmpty()) {
                item { Text("（空）", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
            } else {
                items(liveList.mapIndexed { i, s -> i to s }) { (index, s) ->
                    SourceRow(s.name, s.url) { store.removeLiveAt(index); refresh() }

                }
            }
        }
    }
}

@Composable
private fun SourceRow(name: String, url: String, adult: Boolean = false, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdultTag(adult)
                Text(name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}
