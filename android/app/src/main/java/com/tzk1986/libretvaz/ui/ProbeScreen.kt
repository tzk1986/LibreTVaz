package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.CmsRepository
import com.tzk1986.libretvaz.data.HealthStore
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.data.ProbeRow
import com.tzk1986.libretvaz.data.SiteIdx
import com.tzk1986.libretvaz.data.SourceStore
import kotlinx.coroutines.launch

/**
 * 连接·点播源测速墙（web renderProbe/probeAll 等价）：逐源 ac=list+首分类列表往返，
 * 完成一源原位刷一行；显示按 ms 升序（未测/失败沉底）；点行=切为当前点播源；
 * 测速墙覆盖全量点播源（含专区）；P0-1 坐标统一：siteIdx=点播池下标，点行经 SiteIdx.fullToPool
 * 换算后上报（专区行不可作首页当前源，点击无效并标「专区」）；全部测完后清理死满 7 天的普通源（专区源豁免）。
 */
@Composable
fun ProbeScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onPick: (sourceIdx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CmsRepository() }
    val health = remember { HealthStore(context) }
    var sources by remember { mutableStateOf(store.vodSources()) } // 全量含专区：行下标为全量坐标，点行经 fullToPool 换算
    var rows by remember { mutableStateOf<List<ProbeRow>?>(null) } // 与 sources 下标对齐；null=未检测
    var running by remember { mutableStateOf(false) }
    var seq by remember { mutableIntStateOf(0) }

    fun startProbe() {
        if (sources.isEmpty()) { rows = emptyList(); return }
        val my = seq + 1; seq = my
        rows = sources.map { ProbeRow(it.name, it.url, null, "检测中…") }
        running = true
        scope.launch {
            health.probeAll(repo, sources) { i, row ->
                if (my == seq) rows = rows?.mapIndexed { j, r -> if (j == i) row else r }
            }
            if (my != seq) return@launch
            running = false
            val (kept, removed) = health.pruneDead(store.vodSources())
            if (removed > 0) {
                store.replaceVod(kept)
                sources = store.vodSources()
                rows = null // 下标已变，清空待重测
            }
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { if (rows == null && sources.isNotEmpty()) startProbe() }

    // ms 升序墙：已测按 ms 升序在前，检测中/未测按原源序沉底
    val shown = rows?.mapIndexed { i, r -> i to r }?.sortedWith(
        compareBy({ (_, r) -> if (r.ms == null) 1 else 0 },
            { (_, r) -> r.ms ?: Long.MAX_VALUE },
            { (i, _) -> i }),
    ) ?: emptyList()

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TmBtn(onClick = onBack) { Text("← 返回") }
            Text("连接 · 点播源测速", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TmBtn(onClick = { startProbe() }, enabled = !running) { Text(if (running) "检测中…" else "重新检测") }
        }
        Text("逐源请求 ac=list + 首分类列表；连续失败 3 次标失效，死满 7 天自动清理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        if (sources.isEmpty()) {
            Text("无点播源，请先在「源管理」导入", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { (i, _) -> i }) { (idx, row) ->
                val dead = health.isDead(row.url)
                val adult = sources.getOrNull(idx)?.isAdult == true
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { SiteIdx.fullToPool(sources, idx)?.let(onPick) }.padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (adult) Text("专区·不可设为当前源", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelMedium)
                            if (dead) Text("✗失效", color = TvColors.SpeedSlow, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(row.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(row.state, color = probeColor(row.state), style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.width(230.dp))
                    Text(row.ms?.let { "${it}ms" } ?: "—", color = probeColor(row.state), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

// C34（审计 2026-11-11 #34）：与 speedTagColor 同 token 族，撤散写色值
private fun probeColor(state: String): Color = when {
    state.startsWith("OK") -> TvColors.SpeedFast
    state.contains("失败") || state.contains("失效") || state.contains("为空") || state.contains("无数据") -> TvColors.SpeedSlow
    else -> TvColors.SpeedDim
}
