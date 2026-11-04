package com.tzk1986.libretvaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.tzk1986.libretvaz.data.BuiltinSources
import com.tzk1986.libretvaz.data.DiskCache
import com.tzk1986.libretvaz.data.Http
import com.tzk1986.libretvaz.data.LiveCtx
import com.tzk1986.libretvaz.data.SourceGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tzk1986.libretvaz.data.RemoteHub
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.ui.BrowseScreen
import com.tzk1986.libretvaz.ui.DetailScreen
import com.tzk1986.libretvaz.ui.HistScreen
import com.tzk1986.libretvaz.ui.KeepScreen
import com.tzk1986.libretvaz.ui.LiveScreen
import com.tzk1986.libretvaz.ui.PlayerScreen
import com.tzk1986.libretvaz.ui.ProbeScreen
import com.tzk1986.libretvaz.ui.CollectScreen
import com.tzk1986.libretvaz.ui.SearchScreen
import com.tzk1986.libretvaz.ui.SettingsScreen
import com.tzk1986.libretvaz.ui.SourcesScreen
import com.tzk1986.libretvaz.ui.ZoneScreen
import com.tzk1986.libretvaz.ui.theme.LibreTVazTheme

sealed class Screen {
    data object Live : Screen()
    data object Sources : Screen()
    data object Settings : Screen()
    /** 搜索页双池：zone=true 走资源口池（历史/词库/源池全隔离） */
    data class Search(val zone: Boolean) : Screen()
    /** 聚合搜索结果页：携带词/池/指定源，返回回对应池的搜索页；fromWall=true（首页墙点卡带名搜，用户 2026-11-03）返回直回点播页，不再中转搜索页 */
    data class Collect(val kw: String, val zone: Boolean, val srcSel: com.tzk1986.libretvaz.ui.SpecSrc?, val fromWall: Boolean = false) : Screen()
    data object Browse : Screen()
    data object Probe : Screen()
    data object Zone : Screen()
    /** 四池：点播/专区各自的收藏与历史（zone=true 为专区池，对应 web keep/hist/zonekeep/zonehist） */
    data class Keep(val zone: Boolean, val back: Screen = Browse) : Screen()
    data class Hist(val zone: Boolean, val back: Screen = Browse) : Screen()
    /** 详情页＝播放页（web openDetail 单视图）：页内起播/换线/换集/换源/全屏，不再另设 Play 路由 */
    data class Detail(val item: VodSummary, val backTo: Screen) : Screen()
    /** 直播等单地址场景的轻量播放页；live=true 开直播模式（信息条+数字选台+上下台） */
    data class Player(val url: String, val backTo: Screen, val live: Boolean = false) : Screen()
}

class MainActivity : ComponentActivity() {

    /** B11 退出修剪：仅按低水位收缩磁盘占用；搜索分片/专区角标跨会话保留（web localStorage 语义，7 天硬清兜底） */
    override fun onStop() {
        super.onStop()
        // 报错日志改 7 天滚动保留（用户 2026-11-04）：退出不再清除
        Thread {
            runCatching { DiskCache.trimTo(DiskCache.LOW_WATER_BYTES) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 大屏（用户 2026-11-03）：隐藏系统状态栏，首页顶栏自绘网络+时钟替代
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val sourceStore = SourceStore(this)
        val settingsStore = SettingsStore(this)
        Http.proxyUrl = settingsStore.proxyUrl // 全局 HTTP/播放数据源共用可选代理
        Http.applyGlobalProxy()
        BuiltinSources.seedIfAbsent(this, sourceStore) // 首启自动种入 17 站内置点播源（web BUILTIN_SOURCES 对齐）
        BuiltinSources.seedLiveIfAbsent(this, sourceStore) // 内置直播源种子（B16：全球精筛+中国在线）
        sourceStore.alignZone() // 冷启动归池收编（站单空=静默跳过）
        // 启动守护（web sourceGuard 1.5s 等价）：清死源/24h 订阅重拉/复检/失效切源/后台补测
        lifecycleScope.launch {
            delay(1500)
            runCatching { SourceGuard.run(this@MainActivity, sourceStore, settingsStore) }
                .onFailure { com.tzk1986.libretvaz.data.ErrLog.add("guard", it) }
        }
        setContent {
            LibreTVazTheme {
                // 全局字号钳制 ≤1.15（用户 2026-11-04 定稿，参考 Compose 社区 LocalDensity 覆盖方案）：
                // 手机系统大字号会把 sp 撑爆固定列高度（13T 键盘被裁实测），投影/TV/手机比例统一可控
                val dens = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(dens.density, dens.fontScale.coerceAtMost(1.15f)),
                ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Browse) }
                    // 扫码远程输入：全局长轮询挂活动级，命中派发到目标页（B8，web remoteLoop 等价）
                    var serverUrl by remember { mutableStateOf(settingsStore.serverUrl) }
                    var remoteKw by remember { mutableStateOf<String?>(null) }
                    var remoteSub by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(screen) { serverUrl = settingsStore.serverUrl }
                    LaunchedEffect(serverUrl) {
                        if (serverUrl.isNotEmpty()) RemoteHub.runLoop(serverUrl, this)
                    }
                    DisposableEffect(Unit) {
                        val off = RemoteHub.addHandler { mode, text ->
                            if (mode == "sub") remoteSub = text
                            else {
                                val zone = when (val s = screen) {
                                    is Screen.Search -> s.zone
                                    is Screen.Collect -> s.zone
                                    is Screen.Keep -> s.zone
                                    is Screen.Hist -> s.zone
                                    else -> false
                                }
                                if (screen !is Screen.Search) screen = Screen.Search(zone)
                                remoteKw = text
                            }
                            true
                        }
                        onDispose { off() }
                    }
                    when (val current = screen) {
                        Screen.Live -> LiveScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Browse },
                            onPlay = { idx -> screen = Screen.Player(LiveCtx.chans[idx].url, Screen.Live, live = true) },
                        )
                        Screen.Sources -> SourcesScreen(
                            store = sourceStore,
                            qrAuto = settingsStore.qrAuto,
                            remoteSub = remoteSub,
                            onRemoteSubConsumed = { remoteSub = null },
                            onBack = { screen = Screen.Browse },
                        )
                        Screen.Settings -> SettingsScreen(
                            store = settingsStore,
                            onZone = { screen = Screen.Zone },
                            onBack = { screen = Screen.Browse },
                        )
                        is Screen.Search -> SearchScreen(
                            store = sourceStore,
                            zone = current.zone,
                            remoteKw = remoteKw,
                            onRemoteConsumed = { remoteKw = null },
                            onBack = { screen = if (current.zone) Screen.Zone else Screen.Browse },
                            onCollect = { kw, sel -> screen = Screen.Collect(kw, current.zone, sel) },
                        )
                        is Screen.Collect -> CollectScreen(
                            kw0 = current.kw,
                            zone = current.zone,
                            srcSel = current.srcSel,
                            store = sourceStore,
                            // 墙卡来源：返回=直回点播页（用户 2026-11-03 修复双重搜索页停顿）；搜索页来源仍回搜索页
                            onBack = { screen = if (current.fromWall) Screen.Browse else Screen.Search(current.zone) },
                            onPick = { screen = Screen.Detail(it, current) },
                        )
                        is Screen.Keep -> KeepScreen(
                            zone = current.zone,
                            onBack = { screen = current.back },
                            onPick = { screen = Screen.Detail(it, current) },
                        )
                        is Screen.Hist -> HistScreen(
                            zone = current.zone,
                            onBack = { screen = current.back },
                            onPick = { screen = Screen.Detail(it, current) },
                        )
                        Screen.Browse -> BrowseScreen(
                            store = sourceStore,
                            onSearch = { screen = Screen.Search(false) },
                            onLive = { LiveCtx.wantAuto = true; screen = Screen.Live }, // web 进直播视图自动续播上次台
                            onSources = { screen = Screen.Sources },
                            onSettings = { screen = Screen.Settings },
                            onKeep = { screen = Screen.Keep(false) },
                            onHist = { screen = Screen.Hist(false) },
                            onProbe = { screen = Screen.Probe },
                            onPick = { screen = Screen.Detail(it, Screen.Browse) },
                            // 用户 2026-11-03 修复：墙卡带名搜直达聚合结果页（不再中转搜索页，返回链=详情→结果→点播）
                            onAggSearch = { kw -> screen = Screen.Collect(kw, false, null, fromWall = true) },
                        )
                        Screen.Zone -> ZoneScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Settings },
                            onSearch = { screen = Screen.Search(true) },
                            onKeep = { screen = Screen.Keep(true, Screen.Zone) },
                            onHist = { screen = Screen.Hist(true, Screen.Zone) },
                            onPick = { screen = Screen.Detail(it, Screen.Zone) },
                        )
                        Screen.Probe -> ProbeScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Browse },
                            onPick = { idx ->
                                settingsStore.siteIdx = idx
                                screen = Screen.Browse
                            },
                        )
                        is Screen.Detail -> DetailScreen(
                            item = current.item,
                            onBack = { screen = current.backTo },
                        )
                        is Screen.Player -> PlayerScreen(
                            url = current.url,
                            live = current.live,
                            onBack = { screen = current.backTo },
                        )
                    }
                }
                } // CompositionLocalProvider(fontScale≤1.15)
            }
        }
    }
}
