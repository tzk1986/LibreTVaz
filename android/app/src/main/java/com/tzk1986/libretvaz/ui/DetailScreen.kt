package com.tzk1986.libretvaz.ui

import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.LineSpeed
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 换源/搜关联跳转续播上下文（web App.resume 等价） */
private data class ResumeCtx(val line: Int, val ep: Int, val sec: Long)

private fun fmtHms(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/**
 * 视频详情页＝播放页（web openDetail 1:1）：进页 SWR 缓存秒显并自动续播（fongmi_prog 跨会话进度、
 * 线路按 flag 名还原），视频窗+右侧文字（标题/元信息/导演/主演/简介200），按钮行
 * 「收藏/换源/⚡测速/⛶全屏/«上一集/下一集»」（边界置灰+点击 toast，对齐 syncFsBar off 态），
 * 线路行带 6h 测速标签+单线判慢 swapHint，选集行当前集高亮+续播红点。
 * ⛶ 进沉浸全屏：只留 web 同款 fsBar（上集/播放暂停/下集+片名·线路·第n/N集+退出），任意键唤出 5s 自隐；
 * 返回键分层=全屏→退全屏→回墙。自动换线/连播/测速选快线/autoSrc 链路与原播放页等价（逻辑并入本页）。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    item: VodSummary,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CmsRepository() }
    val store = remember { SourceStore(context) }
    val hist = remember { HistStore(context) }
    val keepSt = remember { KeepStore(context) }
    val settings = remember { SettingsStore(context) }
    val prog = remember { ProgStore(context) }
    val health = remember { HealthStore(context) }

    var itemS by remember { mutableStateOf(item) }
    val source = remember(itemS) { VodSource(itemS.sourceName, itemS.sourceUrl, isAdult = itemS.isAdult) }
    var pendingResume by remember { mutableStateOf<ResumeCtx?>(null) }

    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var errText by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf(0) }
    var curEp by remember { mutableStateOf(0) }
    var tried by remember { mutableStateOf(setOf(0)) }
    var userPickedLine by remember { mutableStateOf(false) }
    var speeds by remember { mutableStateOf<List<Long?>?>(null) }
    var probeSeq by remember { mutableIntStateOf(0) }
    var autoSrcTried by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var errorHint by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(true) }
    var kept by remember { mutableStateOf(keepSt.contains(item)) }
    var finding by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) } // auto 换源链路进行中标记
    var chDlg by remember { mutableStateOf(false) } // 用户 2026-11-03：手动换源=弹框逐源加载+测速（替代行内 changeHits）
    var progHere by remember { mutableStateOf<Prog?>(null) }

    var perfT0 by remember { mutableLongStateOf(0L) } // VodPerf 起播计时（实测比对用）
    var fs by remember { mutableStateOf(false) }
    var fsBar by remember { mutableStateOf(true) }
    var fsSeq by remember { mutableIntStateOf(0) }
    // TVBox 式动作行状态（2026-11-03 定稿清单：拖拽进度/重播/单集循环/倍速/画面比例/重置）
    var fsPos by remember { mutableLongStateOf(0L) }
    var fsDur by remember { mutableLongStateOf(0L) }
    var speedX by remember { mutableFloatStateOf(1f) }
    var zoomMode by remember { mutableStateOf(false) }
    var repeatOne by remember { mutableStateOf(false) }
    var fsPv by remember { mutableStateOf<PlayerView?>(null) }
    val focusReq = remember { FocusRequester() }
    val insetCtrl = remember {
        (context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
    }

    val player = remember { buildPlayer(context) }
    fun lines(): List<PlayLine> = detail?.lines ?: emptyList()
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    fun linkOf(l: Int, e: Int): String? = lines().getOrNull(l)?.list?.getOrNull(e)?.link
    fun epName(): String? = lines().getOrNull(line)?.list?.getOrNull(curEp)?.name
    fun playAt(l: Int, e: Int, startPosMs: Long = 0) {
        val u = linkOf(l, e) ?: return
        errorHint = null
        buffering = true
        perfT0 = android.os.SystemClock.elapsedRealtime()
        Log.i("VodPerf", "playAt line=$l ep=$e url=${u.take(100)}")
        player.setMediaItem(MediaItem.fromUri(u), startPosMs)
        player.prepare()
    }
    fun enterFs() {
        fs = true; fsBar = true; fsSeq++
        insetCtrl?.apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    fun exitFs() {
        fs = false
        insetCtrl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    fun pokeFs() { fsBar = true; fsSeq++ }
    fun seekBy(deltaSec: Long) {
        val dur = player.duration
        if (dur <= 0) { toast("快进/快退暂不可用（流未就绪）"); return }
        val pos = (player.currentPosition / 1000 + deltaSec).coerceIn(0, (dur / 1000) - 1)
        player.seekTo(pos * 1000)
        toast((if (deltaSec < 0) "« 15s " else "15s » ") + fmtHms(pos) + " / " + fmtHms(dur / 1000))
    }

    // ===== 进度持久化（web fongmi_prog：5s 节流/pause 即写/ended 清/孤儿随写清理） =====
    fun writeProg(sec: Long, durSec: Long) {
        if (player.playbackState == Player.STATE_ENDED) return
        val g = lines().getOrNull(line) ?: return
        val p = Prog(line, g.flag, curEp, sec, durSec, System.currentTimeMillis())
        prog.save(itemS.vodId, itemS.sourceKey, p, ProgStore.aliveKeys(context))
        progHere = p
    }

    // ===== TVBox 式动作行（定稿清单；解析/引擎/解码/轨道/弹幕/版本/章节/OP-ED 本场景不适用→隐藏） =====
    fun replayHere() { errorHint = null; playAt(line, curEp); toast("重新播放本集") }
    fun setSpeed(v: Float) { speedX = v; player.setPlaybackSpeed(v) }
    fun cycleSpeed() {
        val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        val next = steps[(steps.indexOf(speedX) + 1).let { if (it < 0) 0 else it } % steps.size]
        setSpeed(next); toast("倍速 ×${next}")
    }
    fun toggleScale() {
        zoomMode = !zoomMode
        fsPv?.resizeMode = if (zoomMode) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        toast(if (zoomMode) "画面：满屏裁切" else "画面：比例适配")
    }
    fun resetView() {
        setSpeed(1f)
        zoomMode = false
        fsPv?.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        toast("已重置倍速与画面")
    }

    // ===== 换源（用户 2026-11-03：手动换源改弹框＝FindSourceDialog matchBest；此函数仅剩 autoSrc 链路：全源找同名直切首个） =====
    /** 跨源跳转（web openDetail(item)+App.resume 等价）：切作品并重载，带线路/集/秒续播 */
    fun openWith(sum: VodSummary, r: ResumeCtx?) {
        pendingResume = r
        errorHint = null
        autoSrcTried = true // 换源后不再级联触发 autoSrc（web 新闭包重置，但同链会自旋）
        itemS = sum
    }
    fun doChangeSource() {
        if (changing) return
        changing = true
        val title = (detail?.title?.ifBlank { null } ?: itemS.name).trim()
        if (title.isEmpty()) { changing = false; return }
        val nt = CmsParser.normalizeTitle(title)
        scope.launch {
            val pool = store.vodSources().filter {
                it.isAdult == itemS.isAdult && PerfCore.norm(it.url) != PerfCore.norm(source.url) &&
                    (health.of(it.url)?.let { h -> h.fails < HealthStore.DEAD_FAILS } ?: true)
            }
            val hits = coroutineScope {
                pool.map { s ->
                    async(Dispatchers.IO) {
                        val list = runCatching { withTimeout(8000) { repo.searchSourcePage1(s, title) } }.getOrDefault(emptyList())
                        list.firstOrNull { CmsParser.normalizeTitle(it.name) == nt }
                            ?: list.firstOrNull { CmsParser.normalizeTitle(it.name).contains(nt) }
                    }
                }.awaitAll().filterNotNull()
            }
            changing = false
            if (hits.isEmpty()) { toast("其它源未找到「$title」"); return@launch }
            openWith(hits[0], ResumeCtx(line, curEp, player.currentPosition / 1000))
        }
    }

    // ===== 测速（B4/H5 复用：LineSpeed.cached 已含 6h 磁盘回灌） =====
    fun playingHealthy() = player.playbackState == Player.STATE_READY && player.currentPosition > 0
    fun tagFor(i: Int): PerfCore.SpeedTag? {
        val k = speeds?.getOrNull(i) ?: return null
        var t = PerfCore.lineSpeedTag(k) ?: return null
        if (t.cls == "sp-bad" && i == line && playingHealthy()) {
            t = PerfCore.SpeedTag(if (k > 0) PerfCore.fmtSpeed(k) + " · 播放中" else "播放中（测速失败）", "sp-mid")
        }
        return t
    }
    fun maybeAllBadAutoSrc(sp: List<Long?>) {
        val ls = lines()
        if (settings.autoSrc && !autoSrcTried && ls.size in 1..2 && sp.isNotEmpty() &&
            sp.all { it != null && it >= 0 && it < PerfCore.SPEED_FAIR }
        ) {
            autoSrcTried = true
            toast("本作该源各线路均偏慢，自动搜关联换源…")
            doChangeSource()
        }
    }
    fun probeSpeeds(epIdx: Int) {
        val ls = lines()
        if (!settings.lineSpeed || ls.isEmpty()) return
        val seq = probeSeq + 1; probeSeq = seq
        val key = LineSpeed.key(itemS.sourceKey, itemS.vodId, epIdx)
        if (LineSpeed.cached(key) == null) speeds = List(minOf(ls.size, LineSpeed.MAX_LINES)) { null }
        scope.launch {
            val sp = LineSpeed.measure(key, ls, epIdx, line, { playingHealthy() }) {
                if (seq == probeSeq) speeds = it
            }
            if (seq != probeSeq) return@launch
            speeds = sp
            var finalSp = sp
            if (sp.getOrNull(line) == 0L && playingHealthy()) {
                finalSp = sp.toMutableList().also { it[line] = -1L }
                LineSpeed.put(key, finalSp); speeds = finalSp
            }
            if (userPickedLine || !settings.autoBestLine || curEp != epIdx) { maybeAllBadAutoSrc(finalSp); return@launch }
            val best = PerfCore.pickBestLine(lines(), finalSp, epIdx)
            if (best < 0 || best == line) { maybeAllBadAutoSrc(finalSp); return@launch }
            if (best >= lines().size) return@launch
            val eIdx = epIdx.coerceAtMost(lines()[best].list.size - 1)
            val ms = player.currentPosition
            line = best; curEp = eIdx; tried = setOf(best)
            toast("已切换到最快线路「${lines()[best].flag}」")
            playAt(best, eIdx, ms)
        }
    }

    // ===== 换集/换线（web goEp/flagRow.onclick 等价；边界=置灰样式+点击 toast） =====
    fun goEp(step: Int) {
        val g = lines().getOrNull(line) ?: return
        val ni = curEp + step
        if (ni < 0) { toast("已是第一集"); return }
        if (ni >= g.list.size) { toast("已是最后一集"); return }
        curEp = ni
        tried = setOf(line)
        playAt(line, ni)
        probeSpeeds(ni)
    }
    fun goEpTo(i: Int) {
        curEp = i
        tried = setOf(line)
        playAt(line, i)
        probeSpeeds(i)
        if (settings.epFull && !fs) enterFs() // web epRow.onclick：epFull 用户手势内自动全屏
    }
    fun switchLine(i: Int) {
        line = i; curEp = 0
        tried = setOf(i)
        userPickedLine = true
        toast("正在切换线路至「${lines()[i].flag}」")
        probeSpeeds(0)
        playAt(i, 0)
    }

    // ===== 渲染（web paint 等价）：默认线→6h 快线复用→resume/prog 覆盖→起播+测速 =====
    fun paint(d: VideoDetail, r: ResumeCtx?) {
        detail = d
        kept = keepSt.contains(itemS)
        val ph = prog.get(itemS.vodId, itemS.sourceKey)
        progHere = ph
        var l = Playback.pickDefaultLine(d.lines).coerceAtLeast(0)
        if (settings.lineSpeed) {
            val sp0 = LineSpeed.cached(LineSpeed.key(itemS.sourceKey, itemS.vodId, 0))
            if (sp0 != null && sp0.any { it != null && it >= PerfCore.SPEED_FAIR }) {
                val b = PerfCore.pickBestLine(d.lines, sp0, 0)
                if (b >= 0) l = b
            }
        }
        var e = 0
        var sec = 0L
        var fromProg = false
        userPickedLine = false
        if (r != null) {
            if (d.lines.getOrNull(r.line)?.list?.isNotEmpty() == true) l = r.line
            d.lines.getOrNull(l)?.list?.let { if (it.isNotEmpty()) e = r.ep.coerceIn(0, it.size - 1) }
            sec = r.sec
        } else if (settings.resumePlay && ph != null) {
            val fi = if (ph.flag.isNotEmpty()) d.lines.indexOfFirst { g -> g.flag == ph.flag } else -1
            l = if (fi >= 0) fi else ph.line.coerceIn(0, d.lines.size - 1)
            d.lines.getOrNull(l)?.list?.let { if (it.isNotEmpty()) { e = ph.ep.coerceIn(0, it.size - 1); sec = if (ph.sec >= 5) ph.sec else 0; fromProg = true } }
        }
        line = l; curEp = e
        tried = setOf(l)
        speeds = LineSpeed.cached(LineSpeed.key(itemS.sourceKey, itemS.vodId, e))
        playAt(l, e, sec * 1000)
        probeSpeeds(e)
        when {
            fromProg && (e > 0 || sec >= 5) -> toast("继续播放 第${e + 1}集 ${fmtHms(sec)}")
            r != null && e > 0 -> toast("已在新源第 ${e + 1} 集续播")
        }
    }

    // ===== 播放器监听（注册一次；回调引用 delegate state，语义同旧播放页） =====
    val listener = remember(player) {
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> { buffering = true; Log.i("VodPerf", "buffering pos=${player.currentPosition}") }
                    Player.STATE_READY -> { buffering = false; Log.i("VodPerf", "ready startMs=${player.currentPosition} cost=${android.os.SystemClock.elapsedRealtime() - perfT0}ms") }
                    Player.STATE_ENDED -> {
                        buffering = false
                        prog.clear(itemS.vodId, itemS.sourceKey)
                        progHere = null
                        if (repeatOne) { player.seekTo(0); player.playWhenReady = true; return } // 单集循环：重播本集不连播
                        val g = lines().getOrNull(line) ?: return
                        when {
                            curEp + 1 >= g.list.size -> toast("已播完全部剧集")
                            !settings.autoNext -> toast("本集结束（自动连播已关，可在选集行改点）")
                            else -> { toast("本集结束，自动播放下一集"); goEp(1) }
                        }
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying && player.playbackState != Player.STATE_ENDED && player.currentPosition >= 5000) {
                    writeProg(player.currentPosition / 1000, (player.duration / 1000).coerceAtLeast(0))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                Log.i("VodPerf", "error ${error.errorCodeName}")
                if (!error.errorCodeName.startsWith("ERROR_CODE_IO") && !error.errorCodeName.contains("TIMEOUT")) {
                    com.tzk1986.libretvaz.data.ErrLog.add(
                        "play", "点播 ${error.errorCodeName}: ${error.message} src=${player.currentMediaItem?.localConfiguration?.uri}"
                    )
                }
                if (!settings.autoLine) {
                    errorHint = "播放失败（${error.errorCodeName}），请换线路或换源"
                    return
                }
                val cand = Playback.pickNextLine(lines(), line, curEp, tried, speeds)
                if (cand.isEmpty()) {
                    errorHint = "已尝试全部 ${lines().size} 条线路均无法播放，请「搜关联/换源」"
                    if (settings.autoSrc && !autoSrcTried && lines().size <= 2) {
                        autoSrcTried = true
                        toast("所有线路均无法播放，自动换源搜索本作…")
                        doChangeSource()
                    }
                    return
                }
                val ni = cand[0].index
                tried = tried + ni
                speeds = LineSpeed.cached(LineSpeed.key(itemS.sourceKey, itemS.vodId, curEp))
                line = ni
                toast("该线路无法播放，3 秒后自动切换「${lines().getOrNull(ni)?.flag ?: ""}」")
                scope.launch {
                    delay(3000)
                    if (line == ni) playAt(ni, curEp)
                }
            }
        }
    }

    // 进页/换源：写历史+词权（web openDetail 头部），SWR 缓存秒显→拉新，数据未变不重绘打断播放
    LaunchedEffect(itemS) {
        hist.push(itemS, mergeName = settings.histMerge, maxPerPool = settings.histMax)
        VocabPools.get(context).bump(itemS.name, 2L, itemS.isAdult)
        autoSrcTried = false
        tried = setOf(line)
        speeds = null
        errText = null
        val cached = repo.detailCached(source, itemS.vodId)
        val paintedCached = cached != null && cached.lines.isNotEmpty()
        if (paintedCached) paint(cached!!, pendingResume)
        val d = repo.detail(source, itemS.vodId)
        val r = pendingResume
        pendingResume = null
        if (d == null || d.lines.isEmpty()) {
            if (!paintedCached) errText = if (d == null) "详情失败：请求超时或源接口异常" else "源未返回详情"
            if (!paintedCached) { buffering = false }
            return@LaunchedEffect
        }
        if (paintedCached && d == cached) return@LaunchedEffect
        paint(d, r)
    }

    // timeupdate 5s 节流写进度（web progSave 等价）；顺带 1s 刷新全屏进度条（TVBox mR3 同频）
    LaunchedEffect(player, itemS) {
        var last = 0L
        while (true) {
            delay(1000)
            fsPos = player.currentPosition; fsDur = player.duration.coerceAtLeast(0L)
            if (!player.isPlaying || player.duration <= 0 || player.currentPosition < 5000) continue
            val n = System.currentTimeMillis()
            if (n - last >= 5000) { last = n; writeProg(player.currentPosition / 1000, player.duration / 1000) }
        }
    }

    DisposableEffect(player) {
        player.addListener(listener)
        onDispose {
            if (player.currentPosition >= 5000 && player.duration > 0 && player.playbackState != Player.STATE_ENDED) {
                writeProg(player.currentPosition / 1000, player.duration / 1000)
            }
            player.removeListener(listener); player.release()
        }
    }
    BackHandler { if (fs) exitFs() else onBack() }
    LaunchedEffect(fs, fsSeq, fsBar) {
        if (fs && fsBar) { delay(5000); fsBar = false } // web showFsBar 5s fade
    }
    LaunchedEffect(fs) { if (fs) runCatching { focusReq.requestFocus() } }

    val g = lines().getOrNull(line)
    val total = g?.list?.size ?: 0
    val singleSlow = lines().size == 1 && speeds?.getOrNull(0).let { it != null && it >= 0 && it < PerfCore.SPEED_FAIR }
    val metaLine = listOfNotNull(
        (detail?.typeName ?: itemS.typeName), detail?.year?.ifBlank { null } ?: itemS.year,
        detail?.area?.ifBlank { null } ?: itemS.area,
    ).joinToString(" / ")
    val descFull = detail?.desc?.replace(Regex("<[^>]+>"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    var showDesc by remember { mutableStateOf(false) } // 用户 2026-11-03：简介全文弹窗

    // 遥控按键：全屏内任意键唤回浮层；←→=±15s seek（带位置 toast）、Enter/Space=暂停（web 键位规范）
    val fsKeyMod = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (!fs) return@onPreviewKeyEvent false
        pokeFs()
        when (ev.key) {
            Key.DirectionLeft -> { seekBy(-15); true }
            Key.DirectionRight -> { seekBy(15); true }
            Key.Enter, Key.MediaPlayPause, Key.DirectionCenter -> { player.playWhenReady = !player.playWhenReady; true }
            Key.Escape -> { exitFs(); true }
            else -> false
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
            .background(if (fs) Color.Black else MaterialTheme.colorScheme.background)
            .then(fsKeyMod)
            // 焦点锚在根节点（纯 Compose 目标必成功）：fs 下遥控按键走 fsKeyMod；非 fs 不抢 PlayerView 焦点
            .focusable()
            .focusRequester(focusReq)
            .pointerInput(fs) { if (fs) detectTapGestures { pokeFs() } },
    ) {
        if (!fs) {
            val wide = maxWidth > maxHeight
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 顶行整体撤销（用户 2026-11-03：返回/搜关联均并入详情按钮行，视频上方不再遮挡）
                // vtop：视频窗 + 文字信息（web .vtop video + .txt）
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                                controllerShowTimeoutMs = 5000
                                findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(15_000L)
                            }
                        },
                        modifier = (if (wide) Modifier.weight(1.1f) else Modifier.fillMaxWidth())
                            .aspectRatio(16f / 9f)
                            .background(Color.Black),
                    )
                    Column(
                        modifier = (if (wide) Modifier.weight(1f) else Modifier.fillMaxWidth()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AdultTag(adultOf(itemS))
                            Text(detail?.title?.ifBlank { null } ?: itemS.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text("· ${itemS.sourceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (metaLine.isNotBlank()) Text(metaLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        detail?.director?.ifBlank { null }?.let { Text("导演：$it", style = MaterialTheme.typography.bodySmall) }
                        detail?.actor?.ifBlank { null }?.let { Text("主演：${it.take(100)}", style = MaterialTheme.typography.bodySmall) }
                        descFull?.let {
                            // 用户 2026-11-03：详情框内容过长截断加省略号，全文经「📖 简介」弹窗查看
                            Text(
                                "简介：${it.take(200)}${if (it.length > 200) "…" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            )
                        }
                        Text(
                            listOfNotNull("正在播放：${itemS.name}", epName()?.let { "· $it" }, if (buffering) "缓冲中…" else null).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (buffering) Color.Yellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        errText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        errorHint?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }
                        // 按钮行（用户 2026-11-03：移入详情框内并增加「📖 简介」；原 web vtxt hrow 收藏/换源/测速/全屏）
                        // FlowRow：横屏半屏放不下 5 钮，换行保证「简介」可见（原 horizontalScroll 会把钮推出屏）
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onBack) { Text("← 返回") } // 用户 2026-11-03：自顶栏移入（顶栏钮遮视频窗）
                            OutlinedButton(onClick = { kept = keepSt.toggle(itemS) }) { Text(if (kept) "★ 已收藏" else "☆ 收藏") }
                            OutlinedButton(onClick = { chDlg = true }) { Text("⇄ 换源") } // 用户 2026-11-03：点击即弹框逐源加载+测速
                            OutlinedButton(onClick = {
                                LineSpeed.delete(LineSpeed.key(itemS.sourceKey, itemS.vodId, curEp))
                                userPickedLine = false
                                speeds = null
                                probeSpeeds(curEp)
                                toast("重新测速各线路…")
                            }) { Text("⚡ 测速") }
                            OutlinedButton(onClick = { enterFs() }) { Text("⛶ 全屏") }
                            OutlinedButton(onClick = { showDesc = true }) { Text("📖 简介") }
                            OutlinedButton(onClick = { finding = true }) { Text("🔎 搜关联") } // 用户 2026-11-03：自顶行并入按钮行
                            // 用户 2026-11-03 定稿：非全屏不放上下集钮（遥控走选集行点选；全屏动作行内有 ⏮/⏭）
                        }
                    }
                }
                // 线路行（web flagRow：flag(集数)+测速标签第二行；单线判慢给 swapHint 出口）
                // 行内换源结果组已撤（用户 2026-11-03：换源改弹框，同名命中+测速在框内逐源增量呈现）
                val ls = lines()
                if (ls.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("线路(共 ${ls.size} 条)", style = MaterialTheme.typography.labelLarge)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ls.forEachIndexed { i, lg ->
                                FilterChip(
                                    selected = i == line,
                                    onClick = { if (i != line) switchLine(i) },
                                    label = {
                                        Column {
                                            Text("${lg.flag}(${lg.list.size})")
                                            tagFor(i)?.let { Text(it.label, color = speedTagColor(it.cls), style = MaterialTheme.typography.labelSmall) }
                                        }
                                    },
                                )
                            }
                            if (singleSlow) {
                                OutlinedButton(onClick = { chDlg = true }) { Text("⚡ 该源仅此1条线 · 换源", maxLines = 1) } // 改开换源弹框（同名+测速直达）
                            }
                        }
                    }
                    // 选集行（web epRow：当前集 on + 续播位 prog 红点）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("选集", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            g?.list?.forEachIndexed { i, e ->
                                Box {
                                    Button(
                                        onClick = { goEpTo(i) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (i == curEp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) { Text(e.name, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                    if (progHere != null && progHere!!.ep == i && i != curEp) {
                                        Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(8.dp).background(Color(0xFFE53935), CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ===== 全屏层＝web fsBar 信息 + TVBox 式面板（2026-11-03 定稿：进度行+动作行；不适用钮隐藏） =====
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply {
                    this.player = player; useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    fsPv = this
                } },
                modifier = Modifier.fillMaxSize(),
            )
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 进度行（TVBox PlayerSeekView 等价）：可拖 seek + 时间/总时长（1s 刷新）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(fmtHms(fsPos / 1000), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = fsPos.toFloat().coerceAtMost((if (fsDur > 0) fsDur else 1L).toFloat()),
                            onValueChange = { v -> fsPos = v.toLong(); pokeFs() },
                            onValueChangeFinished = { player.seekTo(fsPos); pokeFs() },
                            valueRange = 0f..(if (fsDur > 0) fsDur.toFloat() else 1f),
                            enabled = fsDur > 0,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
                    }
                    // 信息行（web fsBar fsInfo：片名·线路·第n/N集）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${detail?.title?.ifBlank { null } ?: itemS.name}${g?.flag?.let { " · $it" } ?: ""} · 第${curEp + 1}/${total}集",
                            color = Color(0xFFDDDDDD), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f),
                        )
                        if (buffering) Text("缓冲中…", color = Color.Yellow, style = MaterialTheme.typography.bodySmall)
                    }
                    // 动作行（横滚，对齐 view_control_vod_action 排布；边界置灰沿用 web）
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { goEp(-1) }) { Text("⏮ 上一集", color = if (curEp <= 0) Color.Gray else Color.White) }
                        TextButton(onClick = { player.playWhenReady = !player.playWhenReady }) { Text(if (playing) "⏯ 暂停" else "⏯ 播放", color = Color.White) }
                        TextButton(onClick = { goEp(1) }) { Text("下一集 ⏭", color = if (total > 0 && curEp >= total - 1) Color.Gray else Color.White) }
                        TextButton(onClick = { replayHere() }) { Text("↻ 重播", color = Color.White) }
                        TextButton(onClick = {
                            repeatOne = !repeatOne
                            toast(if (repeatOne) "已开启单集循环" else "已关闭单集循环")
                        }) { Text(if (repeatOne) "⟳ 循环·开" else "⟳ 循环·关", color = if (repeatOne) Color(0xFF8BC34A) else Color.White) }
                        TextButton(onClick = { cycleSpeed() }) { Text("▶ ${speedX}x", color = if (speedX != 1f) Color.Yellow else Color.White) }
                        TextButton(onClick = { toggleScale() }) { Text("▭ ${if (zoomMode) "满屏" else "适配"}", color = Color.White) }
                        TextButton(onClick = { resetView() }) { Text("⌂ 重置", color = Color.White) }
                        TextButton(onClick = { exitFs() }) { Text("⛶ 退出全屏", color = Color.White) }
                    }
                }
            }
        }
    }

    if (finding) FindSourceDialog(
        store = store,
        title = detail?.title?.ifBlank { null } ?: itemS.name,
        zone = itemS.isAdult,
        onDismiss = { finding = false },
        onPick = { sum ->
            finding = false
            openWith(sum, ResumeCtx(line, curEp, player.currentPosition / 1000)) // D6：带线路/集/进度跨源续播
        },
    )

    // 换源弹框（用户 2026-11-03）：点「⇄ 换源」即开，逐源同名增量+⚡测速，点行跨源续播；BACK 关框回详情页
    if (chDlg) FindSourceDialog(
        store = store,
        title = detail?.title?.ifBlank { null } ?: itemS.name,
        zone = itemS.isAdult,
        matchBest = true,
        excludeUrl = source.url,
        onDismiss = { chDlg = false },
        onPick = { sum ->
            chDlg = false
            openWith(sum, ResumeCtx(line, curEp, player.currentPosition / 1000))
        },
    )

    // 简介全文弹窗（用户 2026-11-03）：BACK 由 Dialog 自身消费→关闭即回详情页
    if (showDesc) AlertDialog(
        onDismissRequest = { showDesc = false },
        title = { Text(detail?.title?.ifBlank { null } ?: itemS.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (metaLine.isNotBlank()) Text(metaLine, style = MaterialTheme.typography.bodyMedium)
                detail?.director?.ifBlank { null }?.let { Text("导演：$it", style = MaterialTheme.typography.bodySmall) }
                detail?.actor?.ifBlank { null }?.let { Text("主演：$it", style = MaterialTheme.typography.bodySmall) }
                descFull?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ?: Text(if (detail == null) "详情加载中…" else "（无简介）", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { showDesc = false }) { Text("关闭") } },
    )
}
