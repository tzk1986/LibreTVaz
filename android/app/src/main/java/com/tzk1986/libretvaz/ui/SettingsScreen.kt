package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.LineSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页网格（web SETTING_DEFS/LAB_DEFS/TAB_META 等价）：每设置一卡「标签+值」，
 * 点击行内切换或弹选择/编辑窗；导航栏卡=功能行显隐+排序（存 tabsJson，点播恒首位在消费侧保证）；
 * 实验室弹六开关；清理缓存钮清进程内三类缓存（B11 扩磁盘部分）。
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    onBack: () -> Unit,
    onZone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 弹窗列表最大高=屏高 70%（用户 2026-11-04 手机横屏适配：原 420dp 固定值超出手机屏会被裁）
    val dlgMaxH = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    var autoNext by remember { mutableStateOf(store.autoNext) }
    var autoLine by remember { mutableStateOf(store.autoLine) }
    var resume by remember { mutableStateOf(store.resumePlay) }
    var qrAuto by remember { mutableStateOf(store.qrAuto) }
    var threads by remember { mutableStateOf(store.searchThreads) }
    var histMax by remember { mutableStateOf(store.histMax) }
    var cacheTier by remember { mutableStateOf(store.cacheTier) }
    var lineSpeed by remember { mutableStateOf(store.lineSpeed) }
    var autoBestLine by remember { mutableStateOf(store.autoBestLine) }
    var fuzzy by remember { mutableStateOf(store.fuzzy) }
    var autoSrc by remember { mutableStateOf(store.autoSrc) }
    var histMerge by remember { mutableStateOf(store.histMerge) }
    var epFull by remember { mutableStateOf(store.epFull) }
    var purgeMsg by remember { mutableStateOf("点击清理") }
    var errCount by remember { mutableIntStateOf(ErrLog.size()) }
    var dlg by remember { mutableStateOf("") } // server / proxy / tabs / lab / threads / histmax / cache
    var urlDraft by remember { mutableStateOf(store.serverUrl) }
    var proxyDraft by remember { mutableStateOf(store.proxyUrl) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StatusResult?>(null) }
    var proxyMsg by remember { mutableStateOf<String?>(null) }
    var tabOrder by remember { mutableStateOf(loadTabOrder(store)) }
    val urlFocus = remember { FocusRequester() }
    val proxyFocus = remember { FocusRequester() }

    BackHandler { onBack() }
    LaunchedEffect(dlg) {
        runCatching { if (dlg == "server") urlFocus.requestFocus() else if (dlg == "proxy") proxyFocus.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", style = MaterialTheme.typography.bodyMedium) }
            Text("设置", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingCard("自动连播", onOff(autoNext)) { autoNext = !autoNext; store.autoNext = autoNext } }
            item { SettingCard("失败自动换线", onOff(autoLine)) { autoLine = !autoLine; store.autoLine = autoLine } }
            item { SettingCard("跨会话续播", onOff(resume)) { resume = !resume; store.resumePlay = resume } }
            item { SettingCard("扫码自动导入", onOff(qrAuto)) { qrAuto = !qrAuto; store.qrAuto = qrAuto } }
            item { SettingCard("搜索线程（首批并发源数）", "$threads") { dlg = "threads" } }
            item { SettingCard("历史记录条数", "$histMax 条") { dlg = "histmax" } }
            item { SettingCard("缓存时长", CacheTiers.TIERS[cacheTier].label) { dlg = "cache" } }
            item { SettingCard("清理缓存", purgeMsg) {
                // B11 扩磁盘：CMS 分片文件全删 + 视频 SimpleCache 释放重建，报回收体积（web cache_purge 等价）
                scope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        val sz = DiskCache.sizeBytes() + VideoCache.sizeBytes(context)
                        clearSearchShardsCache(); clearZoneCaches(); LineSpeed.clearAll(); Agg.clearAll(); DetailCache.clearAll()
                        DiskCache.clearAll(); VideoCache.clear(context)
                        ErrLog.repersist() // 日志 7 天保留（用户 2026-11-04）：清缓存把 errlog 盘片回写
                        sz
                    }
                    purgeMsg = "已清理内存+磁盘（回收 ${DiskCache.sizeLabel(freed)}）"
                }
            } }
            item { SettingCard("报错日志", "${errCount} 条（7 天滚动保留，过期自动清除）") {
                errCount = ErrLog.size(); dlg = "errlog"
            } }
            item { SettingCard("导航栏", "显隐与排序（${tabOrder.count { it.second }}/${functionCards().size} 显示）") {
                tabOrder = loadTabOrder(store); dlg = "tabs"
            } }
            item { SettingCard("影视仓实验室", "测速/最快线/模糊/自动换源/合并/点集全屏（${
                listOf(lineSpeed, autoBestLine, fuzzy, autoSrc, histMerge, epFull).count { it }
            }/6 开）") { dlg = "lab" } }
            item { SettingCard("服务器地址", store.serverUrl.ifBlank { "未配置（直连模式）" }) { dlg = "server" } }
            item { SettingCard("网络代理", if (store.proxyUrl.isBlank()) "直连" else store.proxyUrl) { dlg = "proxy" } }
        }
    }

    if (dlg == "threads") PickerDialog("搜索线程（其余源 1.2s 后次批发送）",
        listOf(8, 12, 16, 24, 36, 48).map { "$it 源" },
        listOf(8, 12, 16, 24, 36, 48).indexOf(threads).coerceAtLeast(0),
        onDismiss = { dlg = "" }) { i -> threads = listOf(8, 12, 16, 24, 36, 48)[i]; store.searchThreads = threads }

    if (dlg == "histmax") PickerDialog("每池历史记录保留条数",
        listOf("30 条", "60 条", "100 条"), listOf(30, 60, 100).indexOf(histMax).coerceAtLeast(0),
        onDismiss = { dlg = "" }) { i -> histMax = listOf(30, 60, 100)[i]; store.histMax = histMax }

    if (dlg == "cache") PickerDialog("缓存时长档位（聚合+搜索 TTL 一组切换）",
        CacheTiers.TIERS.map { it.label }, cacheTier,
        onDismiss = { dlg = "" }) { i -> cacheTier = i; store.cacheTier = i }

    if (dlg == "lab") AlertDialog(
        onDismissRequest = { dlg = "" },
        confirmButton = { Button(onClick = { dlg = "" }) { Text("完成") } },
        title = { Text("影视仓实验室") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = dlgMaxH)) {
                item { LabRow("播放线路吞吐测速", lineSpeed) { lineSpeed = it; store.lineSpeed = it } }
                item { LabRow("测完自动跳最快线", autoBestLine) { autoBestLine = it; store.autoBestLine = it } }
                item { LabRow("模糊搜索（核心片名互含）", fuzzy) { fuzzy = it; store.fuzzy = it } }
                item { LabRow("全线失败自动搜关联换源", autoSrc) { autoSrc = it; store.autoSrc = it } }
                item { LabRow("历史按片名跨源合并", histMerge) { histMerge = it; store.histMerge = it } }
                item { LabRow("点选剧集后收起控制器", epFull) { epFull = it; store.epFull = it } }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🎦 资源口（二级专区聚合）", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Button(onClick = { dlg = ""; onZone() }) { Text("进入") }
                    }
                }
            }
        },
    )

    // 报错日志查看（用户 2026-11-03）：纯内存最小日志，供拍照反馈；清空钮即 ErrLog.clear()
    if (dlg == "errlog") AlertDialog(
        onDismissRequest = { dlg = "" },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.86f),
        confirmButton = {
            TextButton(onClick = { ErrLog.clear(); errCount = 0; dlg = "" }) { Text("清空日志") }
        },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text("关闭") } },
        title = { Text("报错日志（${ErrLog.size()} 条 · 7 天滚动保留，过期自动清除）") },
        text = {
            Text(
                ErrLog.text(),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                modifier = Modifier.heightIn(max = dlgMaxH).verticalScroll(rememberScrollState()),
            )
        },
    )

    if (dlg == "tabs") AlertDialog(
        onDismissRequest = { dlg = "" },
        confirmButton = {
            Button(onClick = { store.tabsJson = saveTabsJson(tabOrder); dlg = "" }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text("取消") } },
        title = { Text("导航栏：↑↓ 调序，开关控显隐（点播为首页不入栏，刷新固定在栏首）") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = dlgMaxH)) {
                itemsIndexed(tabOrder) { i, (id, show) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(functionCards().first { it.id == id }.title, modifier = Modifier.weight(1f))
                        Switch(checked = show, onCheckedChange = { v ->
                            tabOrder = tabOrder.toMutableList().also { it[i] = id to v }
                        })
                        TextButton(onClick = {
                            if (i > 0) tabOrder = tabOrder.toMutableList().also { m -> m[i] = m[i - 1]; m[i - 1] = id to show }
                        }, enabled = i > 0) { Text("↑") }
                        TextButton(onClick = {
                            if (i < tabOrder.size - 1) tabOrder = tabOrder.toMutableList().also { m -> m[i] = m[i + 1]; m[i + 1] = id to show }
                        }, enabled = i < tabOrder.size - 1) { Text("↓") }
                    }
                }
            }
        },
    )

    if (dlg == "server") AlertDialog(
        onDismissRequest = { dlg = "" },
        confirmButton = {
            Button(onClick = {
                store.serverUrl = urlDraft
                testing = true; result = null
                scope.launch {
                    result = ApiStatusClient.fetchStatus(store.serverUrl)
                    testing = false
                }
            }) { Text("保存并测试连接") }
        },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text("关闭") } },
        title = { Text("LibreTV 服务器地址（配对模式）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it; result = null },
                    label = { Text("如 http://192.168.1.10:8080") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    modifier = Modifier.focusRequester(urlFocus),
                )
                when {
                    testing -> Text("测试中…", style = MaterialTheme.typography.bodyMedium)
                    result is StatusResult.Success -> {
                        val r = (result as StatusResult.Success).status
                        Text("连接成功 · 服务端版本 ${r.version}", color = MaterialTheme.colorScheme.primary)
                        Text(
                            buildString {
                                append(if (r.passwordRequired) "已启用访问密码" else "未设置访问密码")
                                append(" · 预置点播源 ${r.defaultSourceCount}")
                                append(" · 预置直播源 ${r.defaultLiveSourceCount}")
                                append(" · 预置订阅 ${r.defaultSubscriptionCount}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    result is StatusResult.Failure ->
                        Text((result as StatusResult.Failure).message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )

    if (dlg == "proxy") AlertDialog(
        onDismissRequest = { dlg = "" },
        confirmButton = {
            Button(onClick = {
                store.proxyUrl = proxyDraft
                Http.proxyUrl = store.proxyUrl
                Http.applyGlobalProxy()
                proxyMsg = if (Http.proxySpec() != null) "已保存并对接口/播放即时生效" else "已保存（格式无效，按直连处理）"
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text("取消") } },
        title = { Text("HTTP 代理（TV 盒无系统代理时救活被 reset 的源站）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proxyDraft,
                    onValueChange = { proxyDraft = it; proxyMsg = null },
                    label = { Text("host:port 如 192.168.1.5:7890；留空直连") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(proxyFocus),
                )
                proxyMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            }
        },
    )
}

private fun onOff(b: Boolean): String = if (b) "开" else "关"

/** 列表行小方块钮（用户 2026-11-04：默认 Button 48dp 在弹窗列表里过大堆叠，与搜索页 HomeBtn 同款） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SmallPickBtn(label: String, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun SettingCard(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(labels) { i, label ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (i == selectedIndex) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        else SmallPickBtn("选择") { onPick(i); onDismiss() }
                    }
                }
            }
        },
    )
}

@Composable
private fun LabRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 导航栏编辑初始态：tabsJson 存档序（未知键丢弃）+ 缺失项按默认序补显示 */
private fun loadTabOrder(store: SettingsStore): List<Pair<String, Boolean>> {
    val all = functionCards().map { it.id to true }
    if (store.tabsJson.isBlank()) return all
    val saved = runCatching {
        val arr = JSONArray(store.tabsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("nav") to (o.optInt("show", 1) != 0)
        }
    }.getOrDefault(emptyList())
    val ids = all.map { it.first }.toHashSet()
    val out = saved.filter { it.first in ids }.toMutableList() // 旧档遗留的 vod/zone 项一并丢弃
    for ((id, _) in all) {
        if (out.none { it.first == id }) out.add(id to true)
    }
    return out
}

private fun saveTabsJson(order: List<Pair<String, Boolean>>): String {
    val arr = JSONArray()
    order.forEach { (nav, show) -> arr.put(JSONObject().put("nav", nav).put("show", if (show) 1 else 0)) }
    return arr.toString()
}
