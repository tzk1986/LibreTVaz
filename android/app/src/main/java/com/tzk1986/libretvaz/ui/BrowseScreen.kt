package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.VodType

/** 影视首页：按源展示分类导航 + 海报墙（TVBox 风格，直连各 CMS 源自身数据） */
@Composable
fun BrowseScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = remember { CmsRepository() }
    val sources = remember { store.vodSources() }
    var sourceIdx by remember { mutableStateOf(0) }
    var types by remember { mutableStateOf<List<VodType>>(emptyList()) }
    var type by remember { mutableStateOf<VodType?>(null) }
    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var targetPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }
    val source: VodSource? = sources.getOrNull(sourceIdx)

    LaunchedEffect(source) {
        type = null
        types = source?.let { repo.types(it) } ?: emptyList()
        type = types.firstOrNull()
    }

    LaunchedEffect(source, type, targetPage) {
        val s = source; val t = type
        if (s == null || t == null) return@LaunchedEffect
        loading = true
        error = null
        val page = repo.listByType(s, t.id, targetPage)
        if (targetPage == 1) items = page else items = items + page
        canLoadMore = page.isNotEmpty()
        if (page.isEmpty() && targetPage == 1) error = "该分类暂无数据，源可能不支持分类列表"
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Text("影视首页", style = MaterialTheme.typography.headlineSmall)
            if (source != null) {
                Text(source.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (sources.isEmpty()) {
            Text("尚未添加点播源，请先到「源管理」添加或导入", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        if (sources.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources.size) { i ->
                    FilterChip(
                        selected = i == sourceIdx,
                        onClick = { sourceIdx = i; targetPage = 1 },
                        label = { Text(sources[i].name) },
                    )
                }
            }
        }

        if (types.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(types) { t ->
                    FilterChip(
                        selected = t.id == type?.id,
                        onClick = { type = t; targetPage = 1 },
                        label = { Text(t.name) },
                    )
                }
            }
        }

        when {
            loading -> Text("加载中…")
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items.indices.toList(), key = { it }) { i ->
                val item = items[i]
                PosterCard(item = item, onClick = { onPick(item) })
            }
            if (canLoadMore && !loading) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = { targetPage++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: VodSummary, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.pic,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            item.remarks?.let { r ->
                Text(
                    text = r,
                    color = MaterialTheme.colorScheme.onSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdultTag(adultOf(item))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = listOfNotNull(item.year, item.area).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}
