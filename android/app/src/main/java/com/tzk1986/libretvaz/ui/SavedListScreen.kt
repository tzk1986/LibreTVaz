package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
 * 顶部「删除」进删除模式＝点卡勾选批量删（A7 审计 2026-11-11，原逐张即删）+「清空」整池清；
 * 点播池与资源口专区池共用本组件，两池同构）。
 */
@Composable
private fun SavedWall(
    title: String,
    items: List<SavedItem>,
    emptyText: String,
    onBack: () -> Unit,
    onPick: (VodSummary) -> Unit,
    onRemoveMany: (List<VodSummary>) -> Unit,
    onClear: () -> Unit,
    updatedBadge: Boolean = false,
    showSource: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var delMode by remember { mutableStateOf(false) }
    // B20（审计 2026-11-11）：整池清空不可恢复，加二次确认弹窗（对标源页「清除所有点播源」同款）
    var confirmClear by remember { mutableStateOf(false) }
    // A7（审计 2026-11-11）：删除模式=多选批量删（点卡勾选✓，底部操作行删除所选）；索引选择，提交时映射回条目
    val sel = remember { mutableStateListOf<Int>() }
    var confirmDel by remember { mutableStateOf(false) }
    BackHandler {
        if (confirmClear) confirmClear = false
        else if (confirmDel) confirmDel = false
        else if (delMode) { delMode = false; sel.clear() }
        else onBack()
    }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmBtn(onClick = onBack) { Text("← 返回") }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                // 删除模式激活=亮底（TmBtn selected），配合海报红✕角标；统一钮样式（用户 2026-11-05）
                TmBtn(onClick = { delMode = !delMode; sel.clear() }, selected = delMode) { Text(if (delMode) "取消删除" else "删除") }
                TmBtn(onClick = { confirmClear = true }) { Text("清空") }
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
            // 4K 投影崩溃修复（用户 2026-11-11）：撤 key=sourceUrl|vodId（池内有重复，
            // 大 dp 档全挂载暴露重复键即崩）；默认位置键等价安全
            itemsIndexed(items, contentType = { _, _ -> "poster" }) { i, s ->
                Box {
                    PosterCard(item = s.vod, onClick = {
                        // A7（审计 2026-11-11）：删除模式点卡=勾选/取消（原逐张即删改批量提交）
                        if (delMode) { if (i in sel) sel.remove(i) else sel.add(i) } else onPick(s.vod)
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
                    // A7 删除模式角标：勾选=红圆✓，未勾=红圆✕（提示点击勾选）
                    if (delMode) Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            .background(TvColors.Red, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (i in sel) "✓" else "✕", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        // A7 底部操作行（仅删除模式）：已选计数 / 全选切换 / 删除所选（确认弹窗） / 清空勾选
        if (delMode) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("已选 ${sel.size} 项", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            TmBtn(onClick = {
                val all = sel.size == items.size
                sel.clear()
                if (!all) repeat(items.size) { sel.add(it) }
            }) { Text(if (sel.size == items.size) "取消全选" else "全选") }
            TmBtn(onClick = { if (sel.isNotEmpty()) confirmDel = true }) { Text("删除所选") }
            TmBtn(onClick = { sel.clear() }) { Text("清空勾选") }
        }
    }
    if (confirmDel) {
        AlertDialog(
            modifier = Modifier.width(tmDialogW()),
            properties = tmDialogProps,
            onDismissRequest = { confirmDel = false },
            title = { Text("确认删除所选 ${sel.size} 项？") },
            text = { Text("不可恢复。") },
            confirmButton = {
                TmBtn(onClick = {
                    confirmDel = false
                    onRemoveMany(sel.map { items[it].vod })
                    sel.clear(); delMode = false
                }) { Text("删除") }
            },
            dismissButton = { TmBtn(onClick = { confirmDel = false }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            modifier = Modifier.width(tmDialogW()),
            properties = tmDialogProps,
            onDismissRequest = { confirmClear = false },
            title = { Text("确认清空$title？") },
            text = { Text("将删除全部 ${items.size} 条记录，不可恢复。") },
            confirmButton = {
                TmBtn(onClick = {
                    confirmClear = false
                    onClear(); delMode = false
                }) { Text("清空") }
            },
            dismissButton = { TmBtn(onClick = { confirmClear = false }) { Text("取消") } },
        )
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
        onRemoveMany = { store.removeMany(it); items = store.list(zone) },
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
        onRemoveMany = { store.removeMany(it); items = store.list(zone) },
        onClear = { store.clear(zone); items = store.list(zone) },
        showSource = showSource,
        modifier = modifier,
    )
}
