package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.SourceStore

@Composable
fun HomeScreen(
    sourceStore: SourceStore,
    onSearch: () -> Unit,
    onLive: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var vodCount by remember { mutableStateOf(sourceStore.vodSources().size) }
    var liveCount by remember { mutableStateOf(sourceStore.liveSources().size) }

    Column(
        modifier = modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "LibreTVaz",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (vodCount + liveCount == 0) "尚未配置数据源，请先进入「源管理」添加或导入"
            else "已配置：点播源 $vodCount 个 · 直播源 $liveCount 个",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(36.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onSearch, modifier = Modifier.width(280.dp)) {
                Text("搜索观影")
            }
            Button(onClick = onLive, modifier = Modifier.width(280.dp)) {
                Text("看电视")
            }
            OutlinedButton(onClick = onSources, modifier = Modifier.width(280.dp)) {
                Text("源管理（点播 / 直播 / 订阅导入）")
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.width(280.dp)) {
                Text("服务器模式设置（可选）")
            }
        }
    }
}
