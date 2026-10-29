package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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

/**
 * TVBox 同款根页面：
 * 未选分类时首页=源 api 无参请求返回的「最近更新」海报墙（pg 翻页）；
 * 点分类 chips 切换到该分类的 ac=videolist 列表页（对应 TVBox 的 VodActivity）。
 */
@Composable
fun BrowseScreen(
    store: SourceStore,
    onSearch: () -> Unit,
    onLive: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onPick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = remember { CmsRepository() }
    var sources by remember { mutableStateOf(store.vodSources()) }
    var sourceIdx by remember { mutableStateOf(0) }
    var types by remember { mutableStateOf<List<VodType>>(emptyList()) }
    var type by remember { mutableStateOf<VodType?>(null) }
    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var targetPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 从源管理返回时刷新源列表
    LaunchedEffect(Unit) { sources = store.vodSources() }
    if (sourceIdx >= sources.size) sourceIdx = 0
    val source: VodSource? = sources.getOrNull(sourceIdx)

    LaunchedEffect(source) {
        type = null
        types = source?.let { repo.types(it) } ?: emptyList()
    }

    LaunchedEffect(source, type, targetPage) {
        val s = source ?: return@LaunchedEffect
        val t = type
        loading = true
        error = null
        val page = if (t == null) repo.home(s, targetPage) else repo.listByType(s, t.id, targetPage)
        if (targetPage == 1) items = page else items = items + page
        canLoadMore = page.isNotEmpty()
        if (page.isEmpty() && targetPage == 1) {
            error = if (type == null) "源未返回最近更新数据，可点上方分类浏览" else "该分类暂无数据，源可能不支持分类列表"
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "LibreTVaz",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch) { Text("搜索观影") }
            Button(onClick = onLive) { Text("看电视") }
            OutlinedButton(onClick = onSources) { Text("源管理") }
            OutlinedButton(onClick = onSettings) { Text("服务器模式") }
        }

        if (sources.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("尚未配置点播源，导入后即可在此浏览海报墙", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSources) { Text("进入源管理") }
            }
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(sources) { i, s ->
                FilterChip(
                    selected = i == sourceIdx,
                    onClick = { sourceIdx = i; targetPage = 1 },
                    label = { Text(s.name + if (s.isAdult) "·成人" else "") },
                )
            }
        }

        if (types.isNotEmpty()) {
            val typeList = types
            val currentType = type
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = currentType == null,
                        onClick = { type = null; targetPage = 1 },
                        label = { Text("首页") },
                    )
                }
                items(typeList) { t ->
                    FilterChip(
                        selected = t.id == currentType?.id,
                        onClick = { type = t; targetPage = 1 },
                        label = { Text(t.name) },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = type?.name ?: "最近更新",
                style = MaterialTheme.typography.titleMedium,
            )
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (error != null && !loading) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items) { _, item ->
                PosterCard(item = item, onClick = { onPick(item) })
            }
            if (canLoadMore && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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
