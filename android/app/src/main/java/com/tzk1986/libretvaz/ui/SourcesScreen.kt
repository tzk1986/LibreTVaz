package com.tzk1986.libretvaz.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.BuiltinSources
import com.tzk1986.libretvaz.data.HealthStore
import com.tzk1986.libretvaz.data.LiveSource
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceGuard
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSource
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 源管理页（web drawSrcMgr 等价）：顶部四按钮[添加内置已测源/立即更新订阅/自动维护开关/清除所有点播源]；
 * 守护语义提示行（订阅 24h 重拉·死 3 次标失效满 7 天移除·当前源失效自动切最快健康源跳过专区）；
 * 列表三分组=点播·常规 / 专区·仅资源口可见（非空才显示）/ 直播，行内健康徽标 ✓ms / ✗失效。
 * 订阅预设复用 SourceGuard.PRESETS（单一事实源防漂移）。
 */
@Composable
fun SourcesScreen(
    store: SourceStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    qrAuto: Boolean = true,
    remoteSub: String? = null,
    onRemoteSubConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val health = remember { HealthStore(context) }
    var vodList by remember { mutableStateOf(store.vodSources()) }
    var liveList by remember { mutableStateOf(store.liveSources()) }
    var autoMaintain by remember { mutableStateOf(settings.autoMaintain) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var confirmSub by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        vodList = store.vodSources()
        liveList = store.liveSources()
    }

    /** 行内健康徽标（web hb 等价）：无档案不显示；连败满 3 次=✗失效；否则 ✓ms */
    fun hb(u: String): Pair<String, Boolean>? = health.of(u)?.let { h ->
        if (h.fails >= HealthStore.DEAD_FAILS) "✗失效" to true else "✓${h.ms?.takeIf { it > 0 } ?: ""}ms" to false
    }

    fun doImport(target: String) {
        if (target.isBlank() || busy) return
        busy = true
        message = null
        scope.launch {
            message = runCatching {
                val r = store.importFromUrl(target)
                refresh()
                "已导入（${r.format}）：点播 +${r.vodAdded} · 直播 +${r.liveAdded}" +
                    (if (r.repos > 0) " · 仓库 ${r.repos - r.reposFailed}/${r.repos}" else "") +
                    if (r.skipped > 0) "，跳过 ${r.skipped} 个不支持条目（Spider/XML 等）" else ""
            }.getOrElse { e -> "导入失败：${e.message}" }
            busy = false
        }
    }

    // 站单备份恢复（用户 2026-11-04 保密改造：仓库不带私有站单 assets，公开克隆=无源版，装好后导入备份 json 恢复预置）
    fun importPresetFile(uri: Uri, zone: Boolean) {
        scope.launch {
            message = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("无法读取文件")
                }
                val list = BuiltinSources.parsePreset(text).map { VodSource(it.name, it.url, isAdult = zone) }
                require(list.isNotEmpty()) { "文件为空或格式不符（需 [[名称,地址],…] JSON 数组）" }
                val have = store.vodSources().map { PerfCore.norm(it.url) }.toHashSet()
                val add = list.filter { PerfCore.norm(it.url) !in have }
                add.forEach { store.addVod(it) }
                refresh()
                "已导入${if (zone) "专区站单" else "点播清单"}：新增 ${add.size} 条（重复跳过 ${list.size - add.size}）"
            }.getOrElse { e -> "导入失败：${e.message}" }
        }
    }
    val importVodPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> importPresetFile(u, zone = false) } }
    val importZonePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> importPresetFile(u, zone = true) } }

    // 扫码推送订阅（web remoteLoop sub 分支等价）：qrAuto 开=直接导入，关=弹窗遥控确认
    LaunchedEffect(remoteSub) {
        remoteSub?.let { u ->
            onRemoteSubConsumed()
            importUrl = u
            if (qrAuto) doImport(u) else confirmSub = u
        }
    }

    BackHandler { onBack() }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TmBtn(onClick = onBack) { Text("返回") }
            Text("源管理", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }

        // 四按钮组（web smAdd/smSubNow/smAuto/smClear 等价）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TmBtn(onClick = {
                val n = BuiltinSources.addMissing(context, store)
                refresh()
                message = if (n > 0) "已添加 $n 个已测源" else if (BuiltinSources.loadPreset(context).isEmpty()) "本包未携带私有站单（无源版），请用下方「导入备份文件」恢复" else "内置源已全部添加"
            }) { Text("＋ 添加内置已测源") }
            TmBtn(enabled = !busy, onClick = {
                settings.subsTs = 0L // 强制过期 → SourceGuard 内 refreshSubsIfStale 立即重拉合并
                busy = true
                scope.launch {
                    message = runCatching {
                        SourceGuard.run(context, store, settings)
                        "订阅更新完成"
                    }.getOrElse { e -> "订阅更新失败：${e.message}" }
                    refresh(); busy = false
                }
            }) { Text(if (busy) "维护中…" else "立即更新订阅") }
            TmBtn(onClick = {
                autoMaintain = !autoMaintain; settings.autoMaintain = autoMaintain
                Toast.makeText(context, if (autoMaintain) "源自动维护已开启" else "自动维护已关闭：不再测活/更新订阅/自动切换", Toast.LENGTH_SHORT).show()
            }, selected = autoMaintain) { Text("自动维护：${if (autoMaintain) "开" else "关"}") }
            TmBtn(onClick = { confirmClear = true }) { Text("清除所有点播源") }
        }
        Text(
            "订阅 ${store.subs().size} 条（每 24h 自动重拉合并新源）· 死源连续失败 ${HealthStore.DEAD_FAILS} 次标失效，满 7 天自动移除 · 当前源失效自动切换最快健康源（跳过专区）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // 导入订阅链接（TVBox 配置 / LibreTV SourceList / 接口目录）
        Text("订阅导入（自动识别 TVBox / SourceList / 接口目录格式）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = importUrl,
                onValueChange = { importUrl = it },
                label = { Text("配置链接") },
                singleLine = true,
                modifier = Modifier.weight(1f).height(TmFieldHeight), // 用户 2026-11-05：输入框与按钮同高
            )
            TmBtn(enabled = !busy, onClick = { doImport(importUrl) }) { Text(if (busy) "导入中…" else "导入") }
            TmBtn(onClick = { showQr = true }) { Text("📱 扫码导入") }
        }
        // 订阅仓库预设（复用 SourceGuard.PRESETS 单一事实源）：点击即填入并导入，免手输长 URL
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceGuard.PRESETS.forEach { (pname, purl) ->
                TmBtn(
                    enabled = !busy,
                    onClick = { importUrl = purl; doImport(purl) },
                ) { Text(pname) }
            }
        }
        // 站单备份文件导入（无源版救急）：格式同 assets=[[名称,地址],…]，点播/专区二选一按钮分流
        Text("备份文件恢复（无源版导入私有站单）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TmBtn(onClick = { importVodPicker.launch("*/*") }) { Text("📥 导入点播清单") }
            TmBtn(onClick = { importZonePicker.launch("*/*") }) { Text("📥 导入专区站单") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        // 手动添加单个源
        Text("手动添加", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.weight(0.3f).height(TmFieldHeight))
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("接口/播放列表地址") }, singleLine = true, modifier = Modifier.weight(0.7f).height(TmFieldHeight))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TmBtn(onClick = {
                if (store.addVod(VodSource(name, url))) { name = ""; url = ""; refresh(); message = "已添加点播源" }
                else message = "未添加：地址无效或已存在"
            }) { Text("＋ 点播源") }
            TmBtn(onClick = {
                if (store.addLive(LiveSource(name, url))) { name = ""; url = ""; refresh(); message = "已添加直播源" }
                else message = "未添加：地址无效或已存在"
            }) { Text("＋ 直播源") }
        }

        // 三分组（web 等价）：点播·常规 / 专区·仅资源口可见（非空才显示）/ 直播
        // P0-2：删除按 URL 捕获源身份（旧闭包吃渲染下标，并发缩表后 removeAt 越界崩）；未命中=已被并发移除，静默
        val normals = vodList.filter { !it.isAdult }
        val zoneRows = vodList.filter { it.isAdult }
        // 源条数有限（内置+订阅合并数十条），整页随外层 Column 滚动，不做列表回收
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("点播源 · 常规（${normals.size}）", style = MaterialTheme.typography.titleMedium)
            if (normals.isEmpty()) {
                Text("无点播源", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                normals.forEach { s ->
                    SourceRow(s.name, s.url, s.isAdult, hb(s.url)) { store.removeVodByUrl(s.url); refresh() }
                }
            }
            if (zoneRows.isNotEmpty()) {
                Text("专区源 · 仅资源口可见（${zoneRows.size}）", style = MaterialTheme.typography.titleMedium)
                zoneRows.forEach { s ->
                    SourceRow(s.name, s.url, s.isAdult, hb(s.url)) { store.removeVodByUrl(s.url); refresh() }
                }
            }
            Text("直播源（${liveList.size}）", style = MaterialTheme.typography.titleMedium)
            if (liveList.isEmpty()) {
                Text("无直播源", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                liveList.forEach { s ->
                    SourceRow(s.name, s.url) { store.removeLiveByUrl(s.url); refresh() }
                }
            }
        }
    }

    // 清除所有点播源（web smClear askYes 等价）：确认弹窗明示「同时关自动维护防订阅回填」
    if (confirmClear) {
        AlertDialog(
        modifier = Modifier.width(tmDialogW()),
        properties = tmDialogProps,
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TmBtn(onClick = {
                    confirmClear = false
                    settings.autoMaintain = false; autoMaintain = false
                    store.replaceVod(emptyList())
                    settings.siteIdx = 0
                    refresh()
                    message = "已清除所有点播源（自动维护同步关闭，方便手动测试导入；可在「自动维护」钮再打开）"
                }) { Text("清除") }
            },
            dismissButton = { TmBtn(onClick = { confirmClear = false }) { Text("取消") } },
            title = { Text("确认清除所有点播源？") },
            text = { Text("将清空常规与专区全部点播源（含订阅合并结果），并自动关闭「自动维护」避免订阅回填——方便手动测试导入；之后可用「自动维护」按钮重新打开。") },
        )
    }

    if (showQr) RemoteQrDialog(mode = "sub", onDismiss = { showQr = false })

    confirmSub?.let { u ->
        AlertDialog(
        modifier = Modifier.width(tmDialogW()),
        properties = tmDialogProps,
            onDismissRequest = { confirmSub = null },
            title = { Text("收到远程订阅链接，确认导入？") },
            text = { Text(u.take(200), style = MaterialTheme.typography.bodySmall, maxLines = 6) },
            confirmButton = {
                TmBtn(onClick = { confirmSub = null; doImport(u) }) { Text("导入") }
            },
            dismissButton = {
                TmBtn(onClick = { confirmSub = null; message = "已忽略远程订阅导入" }) { Text("忽略") }
            },
        )
    }
}

@Composable
private fun SourceRow(
    name: String,
    url: String,
    adult: Boolean = false,
    badge: Pair<String, Boolean>? = null,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdultTag(adult)
                Text(name, style = MaterialTheme.typography.bodyMedium)
                badge?.let { (text, dead) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
        }
        TmBtn(onClick = onDelete) { Text("删除") }
    }
}
