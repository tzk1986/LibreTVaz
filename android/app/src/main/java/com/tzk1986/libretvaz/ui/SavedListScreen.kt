package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.tzk1986.libretvaz.ui.theme.TvColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HistStore
import com.tzk1986.libretvaz.data.KeepChk
import com.tzk1986.libretvaz.data.KeepStore
import com.tzk1986.libretvaz.data.SavedItem
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.VodSummary

/**
 * 收藏/历史通用海报墙（用户 2026-11-05：历史先改成墙，收藏照同款改造，删除逻辑也一致——
 * 顶部「删除」进删除模式点海报即删 + 「清空」整池清；点播池与资源口专区池共用本组件，两池同构）。
 */
@Composable
private fun SavedWall(
    title: String,
    items: List<SavedItem>,
    emptyText: String,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    onRemove: (VodSummary) -> Unit,
    onClear: () -> Unit,
    updatedBadge: Boolean = false,
    showSource: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var delMode by remember { mutableStateOf(false) }
    BackHandler { if (delMode) delMode = false else onBack() }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmBtn(onClick = onBack) { Text("← 返回") }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                // 删除模式激活=亮底（TmBtn selected），配合海报红✕角标；统一钮样式（用户 2026-11-05）
                TmBtn(onClick = { delMode = !delMode }, selected = delMode) { Text(if (delMode) "取消删除" else "删除") }
                TmBtn(onClick = { onClear(); delMode = false }) { Text("清空") }
            }
        }
        if (items.isEmpty()) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "${it.vod.sourceUrl}|${it.vod.vodId}" }, contentType = { "poster" }) { s ->
                Box {
                    PosterCard(item = s.vod, onClick = {
                        if (delMode) onRemove(s.vod) else onPick(s.vod)
                    }, siteLabel = if (showSource) s.vod.sourceName else null)
                    // 收藏「已更新」角标（web keep 的 updated 标记等价）：删除模式下让位给红✕
                    if (updatedBadge && s.updated && !delMode) Text(
                        text = "更新",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                    // 删除模式角标：红圆✕提示点击即删
                    if (delMode) Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            .background(TvColors.Red, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun KeepScreen(zone: Boolean, onBack: () -> Unit, onPick: (VodSummary) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val showSource = remember { SettingsStore(context).showSourceName }
    val store = remember { KeepStore(context) }
    val repo = remember { CmsRepository() }
    var items by remember { mutableStateOf(store.list(zone)) }
    // web checkKeeps：进页触发比对（30min 门控在引擎内），有变化即刷新
    LaunchedEffect(zone) {
        if (KeepChk.run(repo, store, zone)) items = store.list(zone)
    }
    SavedWall(
        title = if (zone) "专区收藏" else "收藏",
        items = items,
        emptyText = if (zone) "暂无专区收藏，去资源口收藏作品后在此查看" else "暂无收藏，在详情页点「收藏」加入此处",
        onBack = onBack,
        onPick = { store.clearUpdated(it); items = store.list(zone); onPick(it) },
        onRemove = { store.remove(it); items = store.list(zone) },
        onClear = { store.clear(zone); items = store.list(zone) },
        updatedBadge = true,
        showSource = showSource,
        modifier = modifier,
    )
}

/** 历史页（用户 2026-11-05 照 TVBox 截图改海报墙）：普通模式点海报=续播，删除模式点海报=删该条 */
@Composable
fun HistScreen(zone: Boolean, onBack: () -> Unit, onPick: (VodSummary) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val showSource = remember { SettingsStore(context).showSourceName }
    val store = remember { HistStore(context) }
    var items by remember { mutableStateOf(store.list(zone)) }
    SavedWall(
        title = if (zone) "专区历史" else "历史",
        items = items,
        emptyText = if (zone) "暂无专区播放记录" else "暂无播放历史，观看后自动记录在此",
        onBack = onBack,
        onPick = onPick,
        onRemove = { store.remove(it); items = store.list(zone) },
        onClear = { store.clear(zone); items = store.list(zone) },
        showSource = showSource,
        modifier = modifier,
    )
}
