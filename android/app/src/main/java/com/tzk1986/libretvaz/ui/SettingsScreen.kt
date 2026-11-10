package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.border
import com.tzk1986.libretvaz.ui.theme.TvColors
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
    var showSource by remember { mutableStateOf(store.showSourceName) }
    var threads by remember { mutableStateOf(store.searchThreads) }
    var histMax by remember { mutableStateOf(store.histMax) }
    var cacheTier by remember { mutableStateOf(store.cacheTier) }
    var zoomPref by remember { mutableStateOf(store.zoomPref) }
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
            TmBtn(onClick = onBack) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        // 用户 2026-11-05 终改：双列大卡→快搜结果行同款——一行一条、名称靠左、开关/当前值靠右（原卡太大压不下）
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SettingCard("自动连播", onOff(autoNext)) { autoNext = !autoNext; store.autoNext = autoNext } }
            item { SettingCard("失败自动换线", onOff(autoLine)) { autoLine = !autoLine; store.autoLine = autoLine } }
            item { SettingCard("跨会话续播", onOff(resume)) { resume = !resume; store.resumePlay = resume } }
            item { SettingCard("扫码自动导入", onOff(qrAuto)) { qrAuto = !qrAuto; store.qrAuto = qrAuto } }
            // TVBox 对标 C2（2026-11-10）：四墙海报卡源名前缀统一开关
            item { SettingCard("数据源显示", onOff(showSource)) { showSource = !showSource; store.showSourceName = showSource } }
            item { SettingCard("搜索线程（首批并发源数）", "$threads") { dlg = "threads" } }
            item { SettingCard("历史记录条数", "$histMax 条") { dlg = "histmax" } }
            item { SettingCard("缓存时长", CacheTiers.TIERS[cacheTier].label) { dlg = "cache" } }
            // TVBox 对标 C4（2026-11-10）：画面缩放全局档，详情预览+全屏窗初始态（比例钮循环不受档 2 约束）
            item { SettingCard("画面缩放", ZOOM_LABELS[zoomPref]) { dlg = "zoom" } }
            item { SettingCard("清理缓存", purgeMsg) {
                // B11 扩磁盘：CMS 分片文件全删 + 视频 SimpleCache 释放重建，报回收体积（web cache_purge 等价）
                // 用户 2026-11-05：整段 runCatching 兜底——原先未捕获异常直冲 rememberCoroutineScope
                // →Composition 异常处理器＝进程崩溃退出；失败只改行内文案+记 ErrLog「cache」不弹 Toast
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            // 用户 2026-11-04：清缓存纳入图片缓存（Coil 内存+磁盘全清，计入回收体积）
                            val cmsBytes = DiskCache.sizeBytes() + com.tzk1986.libretvaz.data.ImageGc.sizeBytes(context)
                            val videoBytes = VideoCache.sizeBytes(context)
                            clearSearchShardsCache(); clearZoneCaches(); LineSpeed.clearAll(); Agg.clearAll(); DetailCache.clearAll()
                            DiskCache.clearAll()
                            // 视频缓存若仍被播放器占用则跳过（VideoCache.clear 返回 false），不计入回收体积
                            val videoFreed = VideoCache.clear(context)
                            com.tzk1986.libretvaz.data.ImageGc.clear(context)
                            ErrLog.repersist() // 日志 7 天保留（用户 2026-11-04）：清缓存把 errlog 盘片回写
                            (cmsBytes + if (videoFreed) videoBytes else 0L) to videoFreed
                        }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    purgeMsg = r.fold(
                        { (freed, videoFreed) ->
                            "已清理（回收 ${DiskCache.sizeLabel(freed)}）" + if (!videoFreed) "· 视频缓存播放中未清" else ""
                        },
                        { e -> ErrLog.add("cache", e); "清理失败：${e.message ?: e.javaClass.simpleName}" },
                    )
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

    if (dlg == "zoom") PickerDialog("画面缩放（进入详情/全屏时的初始画面档）",
        ZOOM_LABELS, zoomPref,
        onDismiss = { dlg = "" }) { i -> zoomPref = i; store.zoomPref = i }

    // 弹窗压缩（用户 2026-11-05：标题降字号+拉宽+行高收紧，手机一屏看全）
    val dlgWide = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    if (dlg == "lab") AlertDialog(
        onDismissRequest = { dlg = "" },
        properties = dlgWide,
        modifier = Modifier.width(tmDialogW()),
        confirmButton = { TmBtn(onClick = { dlg = "" }) { Text("完成") } },
        title = { Text("影视仓实验室", style = MaterialTheme.typography.titleMedium) },
        text = {
            // 用户 2026-11-05：行间 6dp 间距——卡底相接会连成一整块看不出分隔
            LazyColumn(
                modifier = Modifier.heightIn(max = dlgMaxH),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { LabRow("播放线路吞吐测速", lineSpeed) { lineSpeed = it; store.lineSpeed = it } }
                item { LabRow("测完自动跳最快线", autoBestLine) { autoBestLine = it; store.autoBestLine = it } }
                item { LabRow("模糊搜索（核心片名互含）", fuzzy) { fuzzy = it; store.fuzzy = it } }
                item { LabRow("全线失败自动快搜换源", autoSrc) { autoSrc = it; store.autoSrc = it } }
                item { LabRow("历史按片名跨源合并", histMerge) { histMerge = it; store.histMerge = it } }
                item { LabRow("点选剧集后收起控制器", epFull) { epFull = it; store.epFull = it } }
                // 资源口入口：改选项行同款整行可点（原 48dp TmBtn 把该行撑得比开关行高）
                item {
                    Row(
                        modifier = Modifier.dlgRow().clickable { dlg = ""; onZone() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("🎦 资源口（二级专区聚合）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text("进入", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
            }
        },
    )

    // 报错日志查看（用户 2026-11-03）：纯内存最小日志，供拍照反馈；清空钮即 ErrLog.clear()
    if (dlg == "errlog") AlertDialog(
        onDismissRequest = { dlg = "" },
        properties = dlgWide,
        modifier = Modifier.width(tmDialogW()),
        confirmButton = {
            TmBtn(onClick = { ErrLog.clear(); errCount = 0; dlg = "" }) { Text("清空日志") }
        },
        dismissButton = { TmBtn(onClick = { dlg = "" }) { Text("关闭") } },
        title = {
            Column {
                Text("报错日志（${ErrLog.size()} 条）", style = MaterialTheme.typography.titleMedium)
                Text("7 天滚动保留，过期自动清除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
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
        properties = dlgWide,
        modifier = Modifier.width(tmDialogW()),
        confirmButton = {
            TmBtn(onClick = { store.tabsJson = saveTabsJson(tabOrder); dlg = "" }) { Text("保存") }
        },
        dismissButton = { TmBtn(onClick = { dlg = "" }) { Text("取消") } },
        title = {
            Column {
                Text("导航栏", style = MaterialTheme.typography.titleMedium)
                Text("↑↓ 调序，开关控显隐（点播为首页不入栏，刷新固定在栏首）",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        text = {
            // 用户 2026-11-05：行间 6dp 间距做分隔，字号与其余设置行统一 labelMedium
            LazyColumn(
                modifier = Modifier.heightIn(max = dlgMaxH),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(tabOrder) { i, (id, show) ->
                    Row(
                        modifier = Modifier.dlgRow(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(functionCards().first { it.id == id }.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = show,
                            onCheckedChange = { v -> tabOrder = tabOrder.toMutableList().also { it[i] = id to v } },
                            modifier = Modifier.scale(0.62f).requiredHeight(22.dp), // 用户 2026-11-05：与实验室开关同缩，行高压到文本行级别
                        )
                        TmBtn(onClick = {
                            if (i > 0) tabOrder = tabOrder.toMutableList().also { m -> m[i] = m[i - 1]; m[i - 1] = id to show }
                        }, enabled = i > 0, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("↑") }
                        TmBtn(onClick = {
                            if (i < tabOrder.size - 1) tabOrder = tabOrder.toMutableList().also { m -> m[i] = m[i + 1]; m[i + 1] = id to show }
                        }, enabled = i < tabOrder.size - 1, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("↓") }
                    }
                }
            }
        },
    )

    if (dlg == "server") AlertDialog(
        onDismissRequest = { dlg = "" },
        properties = dlgWide,
        modifier = Modifier.width(tmDialogW()),
        confirmButton = {
            TmBtn(onClick = {
                store.serverUrl = urlDraft
                testing = true; result = null
                scope.launch {
                    result = ApiStatusClient.fetchStatus(store.serverUrl)
                    testing = false
                }
            }) { Text("保存并测试连接") }
        },
        dismissButton = { TmBtn(onClick = { dlg = "" }) { Text("关闭") } },
        title = { Text("LibreTV 服务器地址（配对模式）", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it; result = null },
                    label = { Text("如 http://192.168.1.10:8080") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    modifier = Modifier.focusRequester(urlFocus).height(TmFieldHeight), // 用户 2026-11-05：输入框与按钮同高
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
        properties = dlgWide,
        modifier = Modifier.width(tmDialogW()),
        confirmButton = {
            TmBtn(onClick = {
                store.proxyUrl = proxyDraft
                Http.proxyUrl = store.proxyUrl
                Http.applyGlobalProxy()
                proxyMsg = if (Http.proxySpec() != null) "已保存并对接口/播放即时生效" else "已保存（格式无效，按直连处理）"
            }) { Text("保存") }
        },
        dismissButton = { TmBtn(onClick = { dlg = "" }) { Text("取消") } },
        title = { Text("HTTP 代理（TV 盒无系统代理时救活被 reset 的源站）", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proxyDraft,
                    onValueChange = { proxyDraft = it; proxyMsg = null },
                    label = { Text("host:port 如 192.168.1.5:7890；留空直连") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(proxyFocus).height(TmFieldHeight),
                )
                proxyMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            }
        },
    )
}

private fun onOff(b: Boolean): String = if (b) "开" else "关"

/** 设置行（用户 2026-11-05 终改）：快搜结果行同款单行——白20%底、名称靠左、当前值/开关靠右；
 *  点击即切（开关项开↔关即时换值），行高压到快搜行级别一屏看全 */
@Composable
private fun SettingCard(label: String, value: String, onClick: () -> Unit) {
    // 用户 2026-11-09 #133：遥控焦点=白描边+40%红底（透明描边占位防行高跳动，同全 app 规范）
    var rowF by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().onFocusEvent { rowF = it.isFocused }.clip(RoundedCornerShape(8.dp))
            .then(if (rowF) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(8.dp)) else Modifier)
            .background(if (rowF) TvColors.Red40 else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(max = 360.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        // 用户 2026-11-05：全 app 弹窗统一屏宽 70%（原 0.94×窗口可用区≈屏 63% 不一致）
        modifier = Modifier.width(tmDialogW()),
        confirmButton = {},
        dismissButton = { TmBtn(onClick = onDismiss) { Text("取消") } },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            // 用户 2026-11-05：行间加 6dp 间距——白20%整行底相接会连成一整块，看不见分隔
            LazyColumn(
                modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(labels) { i, label ->
                    // 用户 2026-11-05：选项行改设置行同款——原 bodyMedium 文本 + 48dp 最小高 TmBtn
                    // 同行字号不一、行高被按钮撑不齐；整行可点、左右同 labelMedium、右槽定宽「选择/当前」
                    val cur = i == selectedIndex
                    Row(
                        modifier = Modifier.dlgRow(cur).clickable(enabled = !cur) { onPick(i); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (cur) "当前" else "选择",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (cur) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (cur) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
            }
        },
    )
}

/** 实验室开关行（用户 2026-11-05）：设置行同款白20%卡底+整行可点，弹窗列表另加 6dp 间距做分隔 */
@Composable
private fun LabRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.dlgRow(vPad = 4.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        // 用户 2026-11-05：开关再缩小与「资源口」文本行同高——Switch 自带 48dp 触控高，
        // scale 只缩绘制不缩布局，须配 requiredHeight 才能把行高压到文本行级别
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.scale(0.62f).requiredHeight(22.dp))
    }
}

/** 弹窗内选项行统一卡底（PickerDialog/LabRow/导航栏行共用；间距由 LazyColumn spacedBy 给） */
@Composable
private fun Modifier.dlgRow(selected: Boolean = false, vPad: androidx.compose.ui.unit.Dp = DlgRowPV): Modifier {
    // 用户 2026-11-09 #133：弹窗内选项行同获遥控焦点白框+红底（每调用位独立 remember）
    var rowF by remember { mutableStateOf(false) }
    return this
        .fillMaxWidth()
        .onFocusEvent { rowF = it.isFocused }
        .then(if (rowF) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(8.dp)) else Modifier)
        .clip(RoundedCornerShape(8.dp))
        .background(if (rowF) TvColors.Red40 else if (selected) Color(0x66FFFFFF) else Color(0x33FFFFFF))
        .padding(DlgRowPH, vPad)
}

private val DlgRowPH = 12.dp
private val DlgRowPV = 6.dp

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
