package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    onBrowse: () -> Unit,
    onLive: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var vodCount by remember { mutableStateOf(sourceStore.vodSources().size) }
    var liveCount by remember { mutableStateOf(sourceStore.liveSources().size) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "LibreTVaz",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (vodCount + liveCount == 0) "尚未配置数据源，请先进入「源管理」添加或导入"
            else "已配置：点播源 $vodCount 个 · 直播源 $liveCount 个",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onSources, modifier = Modifier.fillMaxWidth()) {
                Text("源管理（点播 / 直播 / 订阅导入）")
            }
            Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Text("影视首页")
            }
            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                Text("搜索观影")
            }
            Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) {
                Text("看电视")
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text("服务器模式设置（可选）")
            }
        }
    }
}
