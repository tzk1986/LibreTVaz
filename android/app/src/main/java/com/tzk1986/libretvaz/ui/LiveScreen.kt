package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.LiveChannel
import com.tzk1986.libretvaz.data.LivePlaylist
import com.tzk1986.libretvaz.data.LiveRepository
import com.tzk1986.libretvaz.data.SourceStore

@Composable
fun LiveScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onPlay: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources = remember { store.liveSources() }
    val repo = remember { LiveRepository() }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var playlist by remember { mutableStateOf<LivePlaylist?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(selectedIndex, sources) {
        val src = sources.getOrNull(selectedIndex) ?: return@LaunchedEffect
        loading = true; error = null; playlist = null; group = null
        runCatching { repo.load(src) }
            .onSuccess { playlist = it }
            .onFailure { e -> error = "加载失败：${e.message}" }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Text("看电视", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }

        if (sources.isEmpty()) {
            Text("尚未配置直播源，请到「源管理」添加或导入订阅。")
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources.mapIndexed { i, s -> i to s }) { (i, s) ->
                FilterChip(
                    selected = i == selectedIndex,
                    onClick = { selectedIndex = i },
                    label = { Text(s.name) },
                )
            }
        }

        val pl = playlist
        when {
            loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            pl != null -> {
                if (pl.groups.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = group == null, onClick = { group = null }, label = { Text("全部") })
                        }
                        items(pl.groups) { g ->
                            FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g) })
                        }
                    }
                }
                val channels = pl.channels.filter { ch -> group == null || ch.group == group }
                Text("共 ${channels.size} 个频道", style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(channels) { ch -> ChannelRow(ch) { onPlay(ch.url) } }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: LiveChannel, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(channel.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        channel.group?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}
