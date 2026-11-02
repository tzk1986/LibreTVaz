package com.tzk1986.libretvaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tzk1986.libretvaz.data.DiskCache
import com.tzk1986.libretvaz.data.Http
import com.tzk1986.libretvaz.data.RemoteHub
import com.tzk1986.libretvaz.data.SettingsStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VideoDetail
import com.tzk1986.libretvaz.data.VodSource
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
import com.tzk1986.libretvaz.ui.clearSearchShardsCache
import com.tzk1986.libretvaz.ui.clearZoneSpeedCache
import com.tzk1986.libretvaz.ui.ZoneScreen
import com.tzk1986.libretvaz.ui.theme.LibreTVazTheme

sealed class Screen {
    data object Live : Screen()
    data object Sources : Screen()
    data object Settings : Screen()
    /** 搜索页双池：zone=true 走资源口池（历史/词库/源池全隔离） */
    data class Search(val zone: Boolean) : Screen()
    /** 聚合搜索结果页：携带词/池/指定源，返回回对应池的搜索页 */
    data class Collect(val kw: String, val zone: Boolean, val srcSel: com.tzk1986.libretvaz.ui.SpecSrc?) : Screen()
    data object Browse : Screen()
    data object Probe : Screen()
    data object Zone : Screen()
    /** 四池：点播/专区各自的收藏与历史（zone=true 为专区池，对应 web keep/hist/zonekeep/zonehist） */
    data class Keep(val zone: Boolean, val back: Screen = Browse) : Screen()
    data class Hist(val zone: Boolean, val back: Screen = Browse) : Screen()
    data class Detail(val item: VodSummary, val backTo: Screen) : Screen()
    /** 播放页携带完整多线路上下文，页内直接换集/换线/搜本作（对齐 web openDetail 闭包） */
    /** 直播等单地址场景的轻量播放页 */
    data class Player(val url: String, val backTo: Screen) : Screen()
    data class Play(
        val item: VodSummary,
        val source: VodSource,
        val detail: VideoDetail,
        val startLine: Int,
        val startEp: Int,
        val backTo: Screen,
    ) : Screen()
}

class MainActivity : ComponentActivity() {

    /** B11 退出清理：低水位修剪磁盘 + 作废时效性差的搜索分片/专区角标（盘 IO 后台线程，不卡退出动画） */
    override fun onStop() {
        super.onStop()
        Thread {
            runCatching { clearSearchShardsCache() }
            runCatching { clearZoneSpeedCache() }
            runCatching { DiskCache.trimTo(DiskCache.LOW_WATER_BYTES) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceStore = SourceStore(this)
        val settingsStore = SettingsStore(this)
        Http.proxyUrl = settingsStore.proxyUrl // 全局 HTTP/播放数据源共用可选代理
        Http.applyGlobalProxy()
        sourceStore.alignZone() // 冷启动归池收编（站单空=静默跳过）
        setContent {
            LibreTVazTheme {
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
                            onPlay = { url -> screen = Screen.Player(url, Screen.Live) },
                        )
                        Screen.Sources -> SourcesScreen(
                            store = sourceStore,
                            qrAuto = settingsStore.qrAuto,
                            remoteSub = remoteSub,
                            onRemoteSubConsumed = { remoteSub = null },
                            onBack = { screen = Screen.Browse },
                        )
                        Screen.Settings -> SettingsScreen(store = settingsStore, onBack = { screen = Screen.Browse })
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
                            onBack = { screen = Screen.Search(current.zone) },
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
                            onLive = { screen = Screen.Live },
                            onSources = { screen = Screen.Sources },
                            onSettings = { screen = Screen.Settings },
                            onKeep = { screen = Screen.Keep(false) },
                            onHist = { screen = Screen.Hist(false) },
                            onProbe = { screen = Screen.Probe },
                            onZone = { screen = Screen.Zone },
                            onPick = { screen = Screen.Detail(it, Screen.Browse) },
                        )
                        Screen.Zone -> ZoneScreen(
                            store = sourceStore,
                            onBack = { screen = Screen.Browse },
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
                            onPlay = { picked, src, detail, line, ep ->
                                screen = Screen.Play(picked, src, detail, line, ep, current.backTo)
                            },
                        )
                        is Screen.Play -> PlayerScreen(
                            item = current.item,
                            source = current.source,
                            detail = current.detail,
                            startLine = current.startLine,
                            startEp = current.startEp,
                            onBack = { screen = current.backTo },
                        )
                        is Screen.Player -> PlayerScreen(
                            url = current.url,
                            onBack = { screen = current.backTo },
                        )
                    }
                }
            }
        }
    }
}
