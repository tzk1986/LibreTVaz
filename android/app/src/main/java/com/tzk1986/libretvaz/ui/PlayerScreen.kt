package com.tzk1986.libretvaz.ui

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.LineSpeed
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 进程内续播表（对齐 web fongmi_resume 的内存级进度）：key=源|vodId → 线路/集/毫秒 */
private val RESUME_MAP = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, Int, Long>>()
private fun resumeKey(it: VodSummary) = "${it.sourceKey}|${it.vodId}"

/** 测速标签配色（对齐 web sp-ok/sp-mid/sp-bad/sp-dim 语义色） */
internal fun speedTagColor(cls: String): Color = when (cls) {
    "sp-ok" -> Color(0xFF66BB6A)
    "sp-mid" -> Color(0xFFFFC107)
    "sp-bad" -> Color(0xFFEF5350)
    else -> Color(0xFF9E9E9E)
}

private fun buildPlayer(context: android.content.Context): ExoPlayer {
    // 代理经 Http.applyGlobalProxy() 的 JVM 全局 ProxySelector 生效（media3 无 setProxy API）
    val dsf = DefaultHttpDataSource.Factory()
        .setUserAgent(Http.CHROME_UA)
        .setAllowCrossProtocolRedirects(true)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
        .build()
        .apply { playWhenReady = true }
}

/**
 * 点播播放页（对应 web 全屏播控浮层方案）：多线路/选集/上下集边界置灰/自动连播/失败自动换线/
 * ±15s 快进退（DPAD 左右，DefaultTimeBar keyProgressIncrement=15）/搜本作跨源续播。
 * TV 上播放界面即全屏；顶栏随 PlayerView 控制器同步显隐（5s 无操作收起，任意键唤回）。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    item: VodSummary,
    source: VodSource,
    detail: VideoDetail,
    startLine: Int,
    startEp: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val store = remember { SourceStore(context) }
    val repo = remember { CmsRepository() }
    BackHandler { onBack() }

    var detailS by remember { mutableStateOf(detail) }
    var itemS by remember { mutableStateOf(item) }
    var line by remember { mutableStateOf(startLine.coerceIn(0, (detail.lines.size - 1).coerceAtLeast(0))) }
    var ep by remember { mutableStateOf(
        startEp.coerceIn(0, (detail.lines.getOrNull(startLine)?.list?.size ?: 1) - 1)
    ) }
    var tried by remember { mutableStateOf(setOf(line)) }
    var buffering by remember { mutableStateOf(true) }
    var errorHint by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var dlg by remember { mutableStateOf("") } // "" / line / ep / find
    // B4 线路测速：当前「作品|集」的速度表（null 元素=未测；-1=采样失败但播放正常）
    var speeds by remember { mutableStateOf<List<Long?>?>(null) }
    var userPickedLine by remember { mutableStateOf(false) }
    var probeSeq by remember { mutableIntStateOf(0) }

    val player = remember { buildPlayer(context) }

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    fun linkOf(l: Int, e: Int): String? = detailS.lines.getOrNull(l)?.list?.getOrNull(e)?.link
    fun playAt(l: Int, e: Int, startPosMs: Long = 0) {
        val u = linkOf(l, e) ?: return
        errorHint = null
        buffering = true
        player.setMediaItem(MediaItem.fromUri(u), startPosMs)
        player.prepare()
    }
    // ===== B4 线路测速（对齐 web speedProbe/flagBtnHtml）=====
    fun playingHealthy() = player.playbackState == Player.STATE_READY && player.currentPosition > 0
    /** 假阴性防护：判慢但播放器实际流畅 → 黄标「播放中」，不判死（web flagBtnHtml） */
    fun tagFor(i: Int): PerfCore.SpeedTag? {
        val k = speeds?.getOrNull(i) ?: return null
        var t = PerfCore.lineSpeedTag(k) ?: return null
        if (t.cls == "sp-bad" && i == line && playingHealthy()) {
            t = PerfCore.SpeedTag(if (k > 0) PerfCore.fmtSpeed(k) + " · 播放中" else "播放中（测速失败）", "sp-mid")
        }
        return t
    }
    fun probeSpeeds(epIdx: Int) {
        if (!settings.lineSpeed || detailS.lines.isEmpty()) return
        val seq = probeSeq + 1; probeSeq = seq
        val key = LineSpeed.key(itemS.sourceKey, itemS.vodId, epIdx)
        val lines = detailS.lines
        if (LineSpeed.cached(key) == null) speeds = List(minOf(lines.size, LineSpeed.MAX_LINES)) { null }
        scope.launch {
            val sp = LineSpeed.measure(key, lines, epIdx, line, { playingHealthy() }) {
                if (seq == probeSeq) speeds = it
            }
            if (seq != probeSeq) return@launch
            speeds = sp
            // 起播偏晚补判：全测完当前线仍 0 但播放已流畅 → -1（web speedProbe.then 内同逻辑）
            val curK = sp.getOrNull(line)
            var finalSp = sp
            if (curK == 0L && playingHealthy()) {
                finalSp = sp.toMutableList().also { it[line] = -1L }
                LineSpeed.put(key, finalSp)
                speeds = finalSp
            }
            if (userPickedLine || !settings.autoBestLine || ep != epIdx) return@launch
            val best = PerfCore.pickBestLine(lines, finalSp, epIdx)
            if (best < 0 || best == line || best >= lines.size) return@launch
            val eIdx = epIdx.coerceAtMost(lines[best].list.size - 1)
            val sec = player.currentPosition
            line = best; ep = eIdx; tried = setOf(best)
            toast("已切换到最快线路「${lines[best].flag}」")
            playAt(best, eIdx, sec)
        }
    }

    fun goEp(step: Int) {
        val g = detailS.lines.getOrNull(line) ?: return
        val ni = ep + step
        if (ni < 0) { toast("已是第一集"); return }
        if (ni >= g.list.size) { toast("已是最后一集"); return }
        ep = ni
        tried = setOf(line) // 手动换集重置换线记录（对齐 web goEp）
        playAt(line, ni)
        probeSpeeds(ni) // 换集重测本集各线（对齐 web speedProbe(curEp)）
    }
    // 手动点选集（对齐 web epRow.onclick：curEp=选中; tried 重置为新集起点）
    fun goEpTo(i: Int) {
        ep = i
        tried = setOf(line)
        playAt(line, i)
        probeSpeeds(i)
    }

    // 监听只注册一次；回调内引用的都是 delegate state，读到最新值
    val listener = remember(player) {
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = when (state) {
                Player.STATE_BUFFERING -> { buffering = true }
                Player.STATE_READY -> { buffering = false }
                Player.STATE_ENDED -> {
                    buffering = false
                    val g = detailS.lines.getOrNull(line)
                    if (g != null && ep + 1 < g.list.size && settings.autoNext) {
                        toast("本集结束，自动播放下一集")
                        goEp(1)
                    } else {
                        toast("已播完全部剧集")
                    }
                }
                else -> {}
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                if (!settings.autoLine) {
                    errorHint = "播放失败（${error.errorCodeName}），可手动换线路"
                    return
                }
                val cand = Playback.pickNextLine(detailS.lines, line, ep, tried, speeds)
                if (cand.isEmpty()) {
                    errorHint = "已尝试全部 ${detailS.lines.size} 条线路均无法播放，请「搜关联」换源"
                    return
                }
                val ni = cand[0].index
                tried = tried + ni
                speeds = LineSpeed.cached(LineSpeed.key(itemS.sourceKey, itemS.vodId, ep))
                line = ni
                toast("该线路无法播放，3 秒后自动切换「${detailS.lines[ni].flag}」")
                scope.launch {
                    delay(3000)
                    if (line == ni) playAt(ni, ep) // 期间用户手动换线/换集则放弃
                }
            }
        }
    }
    DisposableEffect(player) {
        player.addListener(listener)
        onDispose {
            RESUME_MAP[resumeKey(itemS)] = Triple(line, ep, player.currentPosition)
            player.removeListener(listener); player.release()
        }
    }
    LaunchedEffect(Unit) {
        var l = line; var e = ep; var pos = 0L
        // 仅默认起播（第 1 集）时应用续播；手动点选具体集视为显式意图
        if (settings.resumePlay && e == 0) {
            RESUME_MAP[resumeKey(itemS)]?.let { (sl, se, sp) ->
                val size = detailS.lines.getOrNull(sl)?.list?.size ?: 0
                if (size > 0) {
                    l = sl; e = se.coerceIn(0, size - 1); pos = sp
                    line = l; ep = e; tried = setOf(l)
                }
            }
        }
        if (pos > 0) toast("已从上次播放位置续播")
        playAt(l, e, pos)
        probeSpeeds(e)
    }

    val g = detailS.lines.getOrNull(line)
    val total = g?.list?.size ?: 0
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 5000
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE },
                    )
                    findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(15_000L)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 顶栏操作行：随控制器显隐；上/下集边界置灰不隐藏（对齐 web 用户定稿）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onBack) { Text("← 返回") }
                OutlinedButton(onClick = { dlg = "line" }) {
                    val tag = tagFor(line)
                    Text(
                        "线路：${g?.flag ?: "-"}（${detailS.lines.size}条）" + (tag?.let { " ${it.label}" } ?: ""),
                        color = tag?.let { speedTagColor(it.cls) } ?: Color.Unspecified,
                    )
                }
                OutlinedButton(onClick = { dlg = "ep" }) { Text("第${ep + 1}/${total}集") }
                Button(onClick = { goEp(-1) }, enabled = ep > 0) { Text("⏮ 上一集") }
                Button(onClick = { goEp(1) }, enabled = total > 0 && ep < total - 1) { Text("下一集 ⏭") }
                OutlinedButton(onClick = { dlg = "find" }) { Text("🔎 搜关联") }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val epName = g?.list?.getOrNull(ep)?.name
            Text(
                listOfNotNull(itemS.name, epName?.let { "· $it" }, "来源：${itemS.sourceName}").joinToString(" "),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (buffering) Text("缓冲中…", color = Color.Yellow, style = MaterialTheme.typography.bodyMedium)
            errorHint?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (dlg == "line") AlertDialog(onDismissRequest = { dlg = "" }, confirmButton = {}, dismissButton = {
        TextButton(onClick = { dlg = "" }) { Text("关闭") }
    }, text = {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(detailS.lines) { i, lg ->
                val tag = tagFor(i)
                TextButton(
                    onClick = {
                        dlg = ""
                        line = i; ep = 0; tried = setOf(i)
                        userPickedLine = true // 手动换线后测速不再抢跳最快线（对齐 web userPickedLine）
                        playAt(i, 0)
                        probeSpeeds(0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${lg.flag}（${lg.list.size}集）${if (i == line) " ◀ 当前" else ""}",
                        modifier = Modifier.weight(1f),
                    )
                    tag?.let {
                        Text(it.label, color = speedTagColor(it.cls), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    })

    if (dlg == "ep" && g != null) AlertDialog(onDismissRequest = { dlg = "" }, confirmButton = {}, dismissButton = {
        TextButton(onClick = { dlg = "" }) { Text("关闭") }
    }, text = {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(g.list) { i, e ->
                TextButton(onClick = {
                    dlg = ""
                    goEpTo(i)
                }, modifier = Modifier.fillMaxWidth()) { Text(e.name, modifier = Modifier.fillMaxWidth()) }
            }
        }
    })

    if (dlg == "find") FindSourceDialog(
        store = store,
        title = detailS.title.ifBlank { itemS.name },
        onDismiss = { dlg = "" },
        onPick = { sum ->
            dlg = ""
            val keepSec = player.currentPosition
            val keepEp = ep
            scope.launch {
                val src = VodSource(sum.sourceName, sum.sourceUrl, isAdult = sum.isAdult)
                val d2 = repo.detail(src, sum.vodId)
                if (d2 == null || d2.lines.isEmpty()) {
                    toast("该源未返回播放地址")
                    return@launch
                }
                val li = Playback.pickDefaultLine(d2.lines).coerceAtLeast(0)
                val ei = minOf(keepEp, (d2.lines[li].list.size - 1).coerceAtLeast(0))
                detailS = d2
                itemS = sum
                line = li; ep = ei; tried = setOf(li)
                userPickedLine = false // 换源视同重新进入播放页（对齐 web openDetail 重跑）
                playAt(li, ei, keepSec)
                probeSpeeds(ei)
                toast("已切换到「${sum.sourceName}」续播")
            }
        },
    )
}

/** 直播等单地址轻量播放页 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    val player = remember(url) {
        buildPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Button(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) { Text("返回") }
    }
}
