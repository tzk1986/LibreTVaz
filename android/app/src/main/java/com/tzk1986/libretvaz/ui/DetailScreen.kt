package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary

@Composable
fun DetailScreen(
    item: VodSummary,
    onBack: () -> Unit,
    onPlay: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = remember { CmsRepository() }
    var episodes by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        episodes = null
        failed = false
        val source = VodSource(item.sourceName, item.sourceUrl, isAdult = item.isAdult)
        val detail = repo.detail(source, item.vodId)
        if (detail == null || detail.episodes.isEmpty()) failed = true else episodes = detail.episodes
    }

    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    listOfNotNull(item.year, item.area, item.typeName, "来源：${item.sourceName}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val eps = episodes
        when {
            failed -> Text("获取播放地址失败：该源可能需要详情页解析（尚未支持），可尝试换源观看", color = MaterialTheme.colorScheme.error)
            eps == null -> Text("正在获取选集…")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(eps) { index, url ->
                    Button(onClick = { onPlay(url) }, modifier = Modifier.fillMaxWidth()) {
                        Text("第${index + 1}集")
                    }
                }
            }
        }
    }
}
