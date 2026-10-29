package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.VodType

/**
 * FongMi leanback 同款根页面：
 * 顶栏站名点击弹源切换窗（SiteDialog）；功能卡片行（点播/直播/搜索/源管理/服务器模式）对应各页面；
 * 首页=源无参请求的「更新推荐」海报墙（滚动到底自动翻页）；点「点播」展开分类 chips 进入分类浏览。
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
    var vodMode by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
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
            error = if (type == null) "源未返回最近更新数据，可点「点播」按分类浏览" else "该分类暂无数据，源可能不支持分类列表"
        }
        loading = false
    }

    val gridState = rememberLazyGridState()
    AutoLoadMore(gridState, items.size, loading, canLoadMore) { targetPage++ }

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
            AssistChip(
                onClick = { showSourcePicker = true },
                label = {
                    Text(
                        source?.name?.let { "当前源：$it" } ?: "未配置点播源",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
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

        // 功能行：对应各页面按钮（源管理/服务器模式即原顶栏按钮的入口）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(functionCards()) { _, card ->
                FuncCard(
                    title = card.title,
                    caption = card.caption,
                    selected = card.id == "vod" && vodMode,
                    onClick = {
                        when (card.id) {
                            "vod" -> vodMode = true
                            "live" -> onLive()
                            "search" -> onSearch()
                            "sources" -> onSources()
                            "settings" -> onSettings()
                        }
                    },
                )
            }
        }

        if (vodMode && types.isNotEmpty()) {
            val typeList = types
            val currentType = type
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = currentType == null,
                        onClick = { type = null; targetPage = 1 },
                        label = { Text("更新推荐") },
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
                text = type?.name ?: "更新推荐",
                style = MaterialTheme.typography.titleMedium,
            )
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (error != null && !loading) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items) { _, item ->
                PosterCard(item = item, onClick = { onPick(item) })
            }
            if (canLoadMore && loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("加载中…", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }

    if (showSourcePicker) {
        val pickerSources = sources
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            confirmButton = {
                TextButton(onClick = { showSourcePicker = false }) { Text("关闭") }
            },
            title = { Text("切换点播源") },
            text = {
                LazyColumn {
                    itemsIndexed(pickerSources) { i, s ->
                        val current = s
                        val selected = i == sourceIdx
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent,
                                )
                                .clickable {
                                    sourceIdx = i
                                    showSourcePicker = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(current.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current.isAdult) Text("成人", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
        )
    }
}

/** 焦点/滚动到最后一项时自动翻页（FongMi onLoadMore 行为） */
@Composable
private fun AutoLoadMore(
    gridState: LazyGridState,
    itemCount: Int,
    loading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount, loading, canLoadMore) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to (itemCount > 0 && canLoadMore && !loading && last >= itemCount - 1)
        }
            .collect { (_, shouldLoad) -> if (shouldLoad) onLoadMore() }
    }
}

private data class FuncCardSpec(val id: String, val title: String, val caption: String)

private fun functionCards() = listOf(
    FuncCardSpec("vod", "点播", "分类浏览"),
    FuncCardSpec("live", "直播", "看电视"),
    FuncCardSpec("search", "搜索", "多源聚合"),
    FuncCardSpec("sources", "源管理", "导入订阅/源设置"),
    FuncCardSpec("settings", "服务器模式", "LibreTV 服务器配置"),
)

@Composable
private fun FuncCard(title: String, caption: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier,
    ) {
        Column(
            modifier = Modifier.width(112.dp).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
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
