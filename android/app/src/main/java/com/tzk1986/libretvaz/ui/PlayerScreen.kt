package com.tzk1986.libretvaz.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tzk1986.libretvaz.data.Epg
import com.tzk1986.libretvaz.data.Http
import com.tzk1986.libretvaz.data.LiveCtx
import com.tzk1986.libretvaz.data.LiveRepository
import com.tzk1986.libretvaz.data.LiveSource
import com.tzk1986.libretvaz.data.LiveStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.VideoCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 测速标签配色（对齐 web sp-ok/sp-mid/sp-bad/sp-dim 语义色） */
internal fun speedTagColor(cls: String): Color = when (cls) {
    "sp-ok" -> Color(0xFF66BB6A)
    "sp-mid" -> Color(0xFFFFC107)
    "sp-bad" -> Color(0xFFEF5350)
    else -> Color(0xFF9E9E9E)
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun buildPlayer(context: android.content.Context, live: Boolean = false, hwDecode: Boolean = true): ExoPlayer {
    // B17 OkHttp 播放数据源：HTTP/2 多路复用+大连接池（HLS 分片密集请求收益最大）。
    // 代理经 Http.applyGlobalProxy() 的 JVM 全局 ProxySelector 生效（OkHttp 默认走 ProxySelector.getDefault）
    // 重定向由 OkHttp 处理：followSslRedirects 默认 true（等价原跨协议放行）
    val dsf = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PlayerHttp.client)
        .setUserAgent(Http.CHROME_UA)
    // B17 缓冲画像：点播=默认深度（50/100s 防装载振荡）仅调低起播阈值 2.5→1.5s；直播=小缓冲低延迟
    // 防卡续（用户 2026-11-06 批准）：点播 rebuffer 恢复水位 5→10s——慢线卡顿后攒更足再复播，防「卡-播-卡」振荡
    val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (live) 15_000 else 50_000,
            if (live) 30_000 else 100_000,
            if (live) 2_000 else 1_500,
            if (live) 3_000 else 10_000,
        )
        .build()
    // B11：前置 200MB LRU 盘缓存（HLS 分片/MP4 Range），回看/重播不白耗流量，也杜绝无限吃盘
    // 用户 2026-11-10 复刻 TVBox 硬解/软解钮：codec 选择在 renderer 创建期定死，
    // 软解=PREFER_SOFTWARE（1.11.1 javap 实锤的公开常量），切换=重建播放器续位重播
    val rf = androidx.media3.exoplayer.DefaultRenderersFactory(context)
        .setMediaCodecSelector(
            if (hwDecode) androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE
        )
    return ExoPlayer.Builder(context)
        .setRenderersFactory(rf)
        .setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.factory(context, dsf)))
        .build()
        .apply {
            // P1-7（审计 2026-11-11）：焦点托管——media3 DefaultAudioFocusManager 仅在
            // handleAudioFocus=true 激活（默认关=旧来电重叠声根因）：短暂 LOSS 暂停、GAIN 自动续，
            // 永久 LOSS 暂停+弃焦。三屏共用本厂（点播详情/Player/Live）一处生效
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            playWhenReady = true
        }
}

/** 全应用共享的播放 OkHttp 客户端（连接池/协议栈复用；媒体请求并发高，池给足）。
 *  防卡续（用户 2026-11-06 批准）：readTimeout 15→30s 防大分片慢 CDN 掐断；
 *  媒体 GET 幂等，连接层 IOException（半开连接/CDN 抖动）自动重试 2 次再抛给播放器换线 */
private object PlayerHttp {
    private val retry = okhttp3.Interceptor { chain ->
        val req = chain.request()
        var last: java.io.IOException = java.io.IOException("retry exhausted")
        for (attempt in 0..2) {
            try {
                return@Interceptor chain.proceed(req)
            } catch (e: java.io.IOException) {
                last = e
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (attempt + 1))
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw last
    }

    val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(retry)
            // 用户 2026-11-06 走查 #1：与 Http 普通 GET 共享连接池（同 CDN 起播/元数据互享 keep-alive 连接）
            .connectionPool(Http.sharedPool)
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
    }
}

/** 下一集预取（用户 2026-11-06 批准）：起播后把「当前线下一集」清单+首段缓冲预拉进
 *  buildPlayer 同一 200MB 盘缓存（VideoCache 数据源栈共享），切集直接命中防转圈。
 *  media3 1.11 的 DefaultPreloadManager 懒建单例、表内常驻仅 1 项（预取新集先撤旧项）；
 *  任何失败静默——预取只加速，绝不拖累播放 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VodPreload {
    private var mgr: androidx.media3.exoplayer.source.preload.DefaultPreloadManager? = null
    private var last: MediaItem? = null

    @Synchronized
    fun preload(context: android.content.Context, link: String) {
        if (link.isBlank()) return
        runCatching {
            val app = context.applicationContext
            val m = mgr ?: androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Builder(
                app,
                androidx.media3.exoplayer.source.preload.DefaultPreloadManager.SimpleRankingDataComparator(),
            ) { androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED }
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        VideoCache.factory(
                            app,
                            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PlayerHttp.client)
                                .setUserAgent(Http.CHROME_UA),
                        ),
                    ),
                )
                .setLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(20_000, 40_000, 2_000, 2_000)
                        .build(),
                )
                .build().also { mgr = it }
            last?.let { m.remove(it) }
            val item = MediaItem.fromUri(link)
            m.add(item, 1)
            last = item
        }
    }
}

/**
 * 直播等单地址轻量播放页（点播逻辑已全部并入 DetailScreen＝web 单视图架构）。
 * live=true 复刻 web livewrap：顶栏(源名/EPG现在/时钟)+底栏(三位频道号/台名/正在播放/线路)+
 * 数字键遮罩选台（1400ms 判满，web inputDigit）+↑↓ 组内环绕换台（web chanStep）。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    val context = LocalContext.current
    val store = remember { LiveStore(context) }
    BackHandler { onBack() }
    // chans 不设 remember：播放页内换源会替换 LiveCtx.chans，需随重组读到新列表
    val chans = if (live) LiveCtx.chans else emptyList()
    var chanIdx by remember { mutableIntStateOf(if (live) LiveCtx.cur else -1) }
    var num by remember { mutableStateOf("") } // web LiveKey.num 数字缓存
    var tick by remember { mutableIntStateOf(0) } // 秒针：驱动时钟与顶栏「现在·节目」
    // ⚠️8 播放页换源（web livewrap 换源钮等价）：选源→拉订阅→优先续同名台，无则 0 台
    val liveSrcs = remember { SourceStore(context).liveSources() }
    val lrepo = remember { LiveRepository(context) }
    val scope = rememberCoroutineScope()
    var showSrcPicker by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var srcGen by remember { mutableIntStateOf(0) } // 换源代数：同名 chanIdx 也强制喂新流
    // A12（审计 2026-11-11）：同 URL 重选不重连——重选当前台时清 playedUrl 水位并 bump reloadGen
    // 作 LaunchedEffect 键触发重 prepare（原需换台再回）。声明须先于 playChan（Kotlin 局部声明序）
    var playedUrl by remember { mutableStateOf(url) }
    var reloadGen by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val composeView = androidx.compose.ui.platform.LocalView.current

    val player = remember(url) {
        buildPlayer(context, live).apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }


    /** web playChan：换台写 last 并切流（读 LiveCtx.chans：换源后闭包里的旧列表不可用） */
    fun playChan(i: Int) {
        val c = LiveCtx.chans.getOrNull(i) ?: return
        if (i == chanIdx && live) { // A12：重选当前台=强制重连（清水位+触发键）
            playedUrl = ""
            reloadGen++
            Toast.makeText(context, "重新连接「${c.name}」", Toast.LENGTH_SHORT).show()
        }
        chanIdx = i
        LiveCtx.cur = i
        store.setLast(LiveCtx.srcIdx, c.url)
    }

    // A3（审计 2026-11-11）：媒体键=播放/暂停；频道/上下曲键=上下台回绕（仅直播态；非直播其余放行系统）
    DisposableEffect(player, live) {
        MediaKeys.handler = { code ->
            when (mediaKeyPlan(code, live)) {
                null -> false
                Mk.Toggle -> { if (player.isPlaying) player.pause() else player.play(); true }
                Mk.NextItem, Mk.PrevItem -> {
                    if (!live) false
                    else {
                        val n = LiveCtx.chans.size
                        if (n > 0) {
                            val d = if (code == 166 || code == 87 || code == 274) 1 else -1 // CH_UP/NEXT/STEP_FWD
                            playChan(((chanIdx + d) % n + n) % n)
                        }
                        true
                    }
                }
                else -> false
            }
        }
        onDispose { MediaKeys.handler = null }
    }

    fun switchSrc(i: Int, s: LiveSource) {
        showSrcPicker = false
        if (i == LiveCtx.srcIdx || switching) return
        scope.launch {
            switching = true
            val oldName = chans.getOrNull(chanIdx)?.name
            val r = runCatching { lrepo.load(s) }
            val pl = r.getOrNull()
            if (pl != null && pl.channels.isNotEmpty()) {
                val epg = runCatching { Epg.load(s.epg) }.getOrNull()
                val idx = pl.channels.indexOfFirst { it.name == oldName }.let { if (it >= 0) it else 0 }
                LiveCtx.srcIdx = i; LiveCtx.srcName = s.name; LiveCtx.chans = pl.channels
                LiveCtx.cat = null; LiveCtx.group = null; LiveCtx.favOnly = false
                LiveCtx.favUrls = store.favUrls(i); LiveCtx.epgIdx = epg
                store.setSrcIdx(i)
                srcGen++
                playChan(idx)
                Toast.makeText(context, "已切换到「${s.name}」", Toast.LENGTH_SHORT).show()
                // 弹框窗灭后按键焦点不回自绘 Column，手动夺回；须等弹框窗销毁动画结束（过早取焦会被清，实测首键丢）
                delay(400)
                repeat(4) { runCatching { focus.requestFocus() }; composeView.requestFocus(); delay(180) }
            } else {
                val e = r.exceptionOrNull()
                if (e != null && !com.tzk1986.libretvaz.data.ErrLog.isExpectedNet(e)) {
                    com.tzk1986.libretvaz.data.ErrLog.add("live", "${s.name} ${s.url} → ${e.javaClass.simpleName}: ${e.message}")
                }
                Toast.makeText(context, "「${s.name}」加载失败，未切换", Toast.LENGTH_LONG).show()
            }
            switching = false
        }
    }

    // 起播/换台后把新地址喂给复用的 player（避免重建页面）
    LaunchedEffect(chanIdx, srcGen, reloadGen) {
        if (!live) return@LaunchedEffect
        val u = chans.getOrNull(chanIdx)?.url ?: return@LaunchedEffect
        if (u != playedUrl) {
            playedUrl = u
            player.setMediaItem(MediaItem.fromUri(u))
            player.prepare()
        }
    }

    // B16 换线：播放错误=当前台下一条同名线路（列表序=速度降序，只前进不回绕防死循环）。
    // 偏离注：web 线路号为静态占位，无换线逻辑；此为用户批准的端上增强
    DisposableEffect(player, chanIdx) {
        if (!live) return@DisposableEffect onDispose { }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val ch = LiveCtx.chans.getOrNull(chanIdx)
                // 线路 IO/超时=死源可预期不记；解码等未知错误才进日志（用户 2026-11-03）
                if (!error.errorCodeName.startsWith("ERROR_CODE_IO") && !error.errorCodeName.contains("TIMEOUT")) {
                    com.tzk1986.libretvaz.data.ErrLog.add(
                        "play", "直播 ${ch?.name ?: "?"} ${error.errorCodeName}: ${error.message} url=${ch?.url}"
                    )
                }
                val ls = LiveCtx.linesOf(chanIdx)
                val at = ls.indexOf(chanIdx)
                when {
                    at >= 0 && at < ls.lastIndex -> {
                        playChan(ls[at + 1])
                        Toast.makeText(context, "线路失败，已切线路 ${at + 2}/${ls.size}", Toast.LENGTH_SHORT).show()
                    }
                    ls.size > 1 -> Toast.makeText(context, "本台 ${ls.size} 条线路均失败，请↑↓换台", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(context, "该频道播放失败，请↑↓换台", Toast.LENGTH_SHORT).show()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 进页写 last + 操作提示（web loadLiveList 尾部 toast，键位文案按 APK 适配）
    LaunchedEffect(Unit) {
        if (!live) return@LaunchedEffect
        chans.getOrNull(chanIdx)?.let { store.setLast(LiveCtx.srcIdx, it.url) }
        Toast.makeText(context, "↑↓ 换台 · 数字键输入频道号 · 返回回频道列表", Toast.LENGTH_SHORT).show()
    }

    // web inputDigit 的 1400ms 定时器：停手即判定，越界报无效频道号
    LaunchedEffect(num) {
        if (num.isEmpty()) return@LaunchedEffect
        delay(1400)
        val n = num.toIntOrNull() ?: 0
        num = ""
        if (n in 1..chans.size) playChan(n - 1)
        else if (n > 0 && chans.isNotEmpty()) Toast.makeText(context, "无效频道号 $n（本流仅 ${chans.size} 个频道）", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) { delay(1000); tick++ }
    }

    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        // PlayerView 让出按键后由本页处理换台/选台；延后重试取焦防首帧竞态（实测首键被吞）。
        // ⚠️API36 实测：仅 FocusRequester 内部取焦不落平台焦点（调用无异常但 findFocus=null），
        // 必须同时让宿主 AndroidComposeView 拿平台焦点，按键才会进本层 onPreviewKeyEvent
        repeat(5) {
            delay(120)
            runCatching { focus.requestFocus() }
            composeView.requestFocus()
        }
    }

    fun digitOf(k: Key): String? = when (k) {
        Key.Zero, Key.NumPad0 -> "0"
        Key.One, Key.NumPad1 -> "1"
        Key.Two, Key.NumPad2 -> "2"
        Key.Three, Key.NumPad3 -> "3"
        Key.Four, Key.NumPad4 -> "4"
        Key.Five, Key.NumPad5 -> "5"
        Key.Six, Key.NumPad6 -> "6"
        Key.Seven, Key.NumPad7 -> "7"
        Key.Eight, Key.NumPad8 -> "8"
        Key.Nine, Key.NumPad9 -> "9"
        else -> null
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // 直播=web livewrap 无播控条；关控制器防 Media3 show() 内部 requestFocus 抢走自绘层焦点（实测首键丢）
                    useController = !live
                    if (live) {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (live) {
            tick // 每秒重读 EPG「现在」与刷新时钟（web updateEpgTop + ltClock）
            val chan = chans.getOrNull(chanIdx)
            val lineIds = LiveCtx.linesOf(chanIdx)
            val lineNo = lineIds.indexOf(chanIdx) + 1
            val epgNow = Epg.now(LiveCtx.epgIdx, chan?.name)
            val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            Column(modifier = Modifier.fillMaxSize().focusable().focusRequester(focus)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.key == Key.DirectionUp -> { LiveCtx.step(-1)?.let { playChan(it) }; true }
                        ev.key == Key.DirectionDown -> { LiveCtx.step(1)?.let { playChan(it) }; true }
                        else -> digitOf(ev.key)?.let { d -> num += d; true } ?: false
                    }
                }) {
                // 顶栏 ltop：返回+源名 · EPG 现在 · 时钟
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TmBtn(onClick = onBack) { Text("返回") }
                    TmBtn(onClick = { if (!switching) showSrcPicker = true }) {
                        Text(if (switching) "切换中…" else "📡 换源")
                    }
                    Text(LiveCtx.srcName.ifEmpty { "直播" }, color = Color.White, maxLines = 1)
                    if (epgNow != null) {
                        val end = if (epgNow.s > 0) Epg.hm(epgNow.s) else "··"
                        Text(
                            "现在 · ${epgNow.title} (${Epg.hm(epgNow.t)}–$end)",
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(clock, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                // 底栏 lbBar：三位频道号 台名 正在播放 线路（线路号=web 静态占位「线路 1」）
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("%03d".format(chanIdx + 1), color = Color.White)
                    Text(chan?.name ?: "—", color = Color.White, maxLines = 1)
                    Text("正在播放：${chan?.name ?: "—"}", color = Color(0xFFDDDDDD), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(if (lineIds.size > 1) "线路 $lineNo/${lineIds.size}" else "线路 1", color = Color(0xFF999999), style = MaterialTheme.typography.bodySmall)
                }
            }
            // 数字遮罩 digital：输入中居中大字
            if (num.isNotEmpty()) {
                Text(
                    num,
                    color = Color.White,
                    fontSize = 64.sp,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        } else {
            TmBtn(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) { Text("返回") }
        }
    }

    if (showSrcPicker) {
        AlertDialog(
        properties = tmDialogProps,
            onDismissRequest = { showSrcPicker = false },
            confirmButton = {},
            dismissButton = { TmBtn(onClick = { showSrcPicker = false }) { Text("取消") } },
            title = { Text("切换直播源") },
            text = {
                if (liveSrcs.size < 2) Text("只有 1 个直播源：到「源管理」添加或导入订阅后可换源")
                else LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(liveSrcs.mapIndexed { i, s -> i to s }) { (i, s) ->
                        TextButton(
                            onClick = { switchSrc(i, s) },
                            enabled = !switching,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                s.name + if (i == LiveCtx.srcIdx) "（当前）" else "",
                                color = if (i == LiveCtx.srcIdx) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        }
                    }
                }
            },
        )
    }
}
