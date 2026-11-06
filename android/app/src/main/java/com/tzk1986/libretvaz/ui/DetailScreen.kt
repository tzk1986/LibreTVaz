package com.tzk1986.libretvaz.ui

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeout

/** 换源/快搜跳转续播上下文（web App.resume 等价） */
private data class ResumeCtx(val line: Int, val ep: Int, val sec: Long)

private fun fmtHms(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** 详情按钮行钮（用户 2026-11-05 参考图边界核对：深色画面上黑底再浓也看不出边——参考图的药丸边界
 *  ＝白色半透底；用户 2026-11-05 终定白 20%（0x33FFFFFF）+白字（30% 黑一闪已废）；
 *  外框改 FilterChip 同款量纲——高度压低（32dp）两侧收紧（无图标 16dp 水平内边距），
 *  长宽比例与快搜弹窗关联词 chip（TmChip）一致） */
@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = {},
        label = { ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() } },
        colors = FilterChipDefaults.filterChipColors(
            // 用户 2026-11-05：选中红框已撤——在播集改「略亮白底」区分（普通白20%，选中白40%，均无框）
            containerColor = if (selected) Color(0x66FFFFFF) else Color(0x33FFFFFF),
            labelColor = Color.White,
            disabledContainerColor = Color(0x1AFFFFFF),
            disabledLabelColor = Color(0x8AFFFFFF),
        ),
        border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
    )
}

/** 全屏动作行图标钮（TVBox 复刻）：off=半透明禁用，active=绿色高亮（循环/倍速/选集开启态）；
 *  图标统一追加 U+FE0E 强制文字字形，防止 ⏸/ 等被系统 emoji 字体渲染成橙色色块（用户 2026-11-04 反馈配色不一致） */
@Composable
private fun FsAct(
    icon: String,
    desc: String,
    off: Boolean = false,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = !off,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.semantics { contentDescription = desc },
    ) {
        Text(
            icon + "︎",
            fontSize = 22.sp,
            color = when {
                off -> Color(0x66FFFFFF)
                tint != null -> tint
                active -> Color(0xFF8BC34A)
                else -> Color.White
            },
        )
    }
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
    var changing by remember { mutableStateOf(false) } // auto 换源链路进行中标记
    // 用户 2026-11-05：换源与搜关联合并为单弹窗单钮，定名「快搜」——chDlg 即入口，框内默认换源海报网格+关联词，点词转全源搜
    var chDlg by remember { mutableStateOf(false) }
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
    // 用户 2026-11-04：TVBox 复刻三件套状态——选集面板 / 顶部分辨率·码率 / 拖拽跟手临时位（-1=未拖）
    var fsEpPanel by remember { mutableStateOf(false) }
    // 看板遥控焦点：自持下标（根节点已占 focusReq，不依赖 Compose 焦点系统；2 列网格，上下=±2）
    var fsFocusEp by remember { mutableIntStateOf(0) }
    val fsGrid = rememberLazyGridState()
    // 用户 2026-11-04：非全屏播放窗可聚焦，遥控 OK=进全屏；选集点选后焦点自动回窗口
    val videoFsReq = remember { FocusRequester() }
    var fsResTxt by remember { mutableStateOf("") }
    var fsBit by remember { mutableStateOf("") }
    var fsDragPos by remember { mutableLongStateOf(-1L) }
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
        player.setMediaItem(MediaItem.fromUri(u), startPosMs)
        player.prepare()
        // 下一集预取+带宽余量闸（用户 2026-11-06 批准；实机复测发现慢线预取抢当前播放带宽反噬换集速度）：
        // 仅当当前线实测吞吐 ≥ 2×节目码率（A4）才预取；未测/判死/码率未知一律跳过，绝不与播放抢流
        linkOf(l, e + 1)?.let { next ->
            val lk = LineSpeed.key(itemS.sourceKey, itemS.vodId, e)
            val kbps = LineSpeed.cached(lk)?.getOrNull(l)
            val bps = LineSpeed.cachedBps(lk)?.getOrNull(l)
            if (kbps != null && kbps > 0 && bps != null && bps > 0 && kbps * 1024 >= 2 * bps) {
                VodPreload.preload(context, next)
            }
        }
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
        // 修复（用户 2026-11-05：退全屏后状态栏永久压住顶部 tab）：全局策略状态栏常隐，
        // 这里只恢复导航栏；systemBars() 会把 hide 标志一并清掉导致状态栏再也回不去
        insetCtrl?.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
    }
    fun pokeFs() { fsBar = true; fsSeq++ }
    // 用户 2026-11-06 走查 #13：快进/快退提示改 FS 底栏内覆盖层文本（1.5s 自隐；系统 Toast 会压浮层且遥控连按堆积）
    var seekHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(1500); seekHint = null } }
    fun seekBy(deltaSec: Long) {
        val dur = player.duration
        if (dur <= 0) { toast("快进/快退暂不可用（流未就绪）"); return }
        val pos = (player.currentPosition / 1000 + deltaSec).coerceIn(0, (dur / 1000) - 1)
        player.seekTo(pos * 1000)
        seekHint = (if (deltaSec < 0) "« 15s " else "15s » ") + fmtHms(pos) + " / " + fmtHms(dur / 1000)
    }

    // ===== 进度持久化（web fongmi_prog：5s 节流/pause 即写/ended 清/孤儿随写清理） =====
    fun writeProg(sec: Long, durSec: Long) {
        if (player.playbackState == Player.STATE_ENDED) return
        val g = lines().getOrNull(line) ?: return
        val p = Prog(line, g.flag, curEp, sec, durSec, System.currentTimeMillis())
        prog.save(itemS.vodId, itemS.sourceKey, p, ProgStore.aliveKeys(context))
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
    // 崩溃修复（2026-11-04）：LineSpeed.measure 的 playingHealthy 回调跑在 IO 协程，而 ExoPlayer 仅主线程可读
    // （FATAL: Player is accessed on the wrong thread）。IO 侧改读主线程 1s 循环维护的快照。
    val healthySnap = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun playingHealthy() = player.playbackState == Player.STATE_READY && player.currentPosition > 0
    fun tagFor(i: Int): PerfCore.SpeedTag? {
        val k = speeds?.getOrNull(i) ?: return null
        // 用户 2026-11-05 批准偏离 web：节目码率已知且余量达标时「慢/不可用」放宽为「可能卡顿」
        var t = PerfCore.lineSpeedTagSoft(k, LineSpeed.cachedBps(LineSpeed.key(itemS.sourceKey, itemS.vodId, curEp))?.getOrNull(i)) ?: return null
        if (t.cls == "sp-bad" && i == line && playingHealthy()) {
            t = PerfCore.SpeedTag(if (k > 0) PerfCore.fmtSpeed(k) + " · 播放中" else "播放中（测速失败）", "sp-mid")
        }
        return t
    }
    // 用户 2026-11-05 照 TVBox 截图：从线路名提取分辨率词单独绿显，剩余作源名
    fun resOf(flag: String): String? =
        Regex("(?i)(4K|1080P|720P|480P|2K|HD|BD|SD)").find(flag)?.value?.uppercase()
    fun maybeAllBadAutoSrc(sp: List<Long?>) {
        val ls = lines()
        if (settings.autoSrc && !autoSrcTried && ls.size in 1..2 && sp.isNotEmpty() &&
            sp.all { it != null && it >= 0 && it < PerfCore.SPEED_FAIR }
        ) {
            autoSrcTried = true
            toast("本作该源各线路均偏慢，自动快搜换源…")
            doChangeSource()
        }
    }
    fun probeSpeeds(epIdx: Int) {
        val ls = lines()
        if (!settings.lineSpeed || ls.isEmpty()) return
        val seq = probeSeq + 1; probeSeq = seq
        val key = LineSpeed.key(itemS.sourceKey, itemS.vodId, epIdx)
        if (LineSpeed.cached(key) == null) speeds = List(minOf(ls.size, LineSpeed.MAX_LINES)) { null }
        healthySnap.set(playingHealthy())
        scope.launch {
            val sp = LineSpeed.measure(key, ls, epIdx, line, { healthySnap.get() }) {
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
            val best = PerfCore.pickBestLine(lines(), finalSp, epIdx, LineSpeed.cachedBps(key))
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
        // 用户 2026-11-05 终改（覆盖 web epRow.onclick 的 epFull 首点即全屏）：首点只切集保持窗口播放，
        // 再点同一在播集才进全屏（epFull 关则全屏钮失效）
        if (i == curEp && !fs) {
            if (settings.epFull) enterFs()
            return
        }
        curEp = i
        tried = setOf(line)
        playAt(line, i)
        probeSpeeds(i)
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
        var l = Playback.pickDefaultLine(d.lines).coerceAtLeast(0)
        if (settings.lineSpeed) {
            val k0 = LineSpeed.key(itemS.sourceKey, itemS.vodId, 0)
            val sp0 = LineSpeed.cached(k0)
            if (sp0 != null && sp0.any { it != null && it >= PerfCore.SPEED_FAIR }) {
                val b = PerfCore.pickBestLine(d.lines, sp0, 0, LineSpeed.cachedBps(k0))
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
                    Player.STATE_BUFFERING -> buffering = true
                    Player.STATE_READY -> buffering = false
                    Player.STATE_ENDED -> {
                        buffering = false
                        prog.clear(itemS.vodId, itemS.sourceKey)
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
                    errorHint = "已尝试全部 ${lines().size} 条线路均无法播放，请「快搜」换源"
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
            healthySnap.set(player.playbackState == Player.STATE_READY && player.currentPosition > 0)
            // TVBox 参考样式顶栏数据：分辨率 + 实时码率（1s 与进度同频）
            val ex = player as? androidx.media3.exoplayer.ExoPlayer
            val vf = ex?.videoFormat
            if (vf != null) {
                fsResTxt = "${vf.width} x ${vf.height}"
                fsBit = when {
                    vf.bitrate >= 1_000_000 -> "%.2f Mbps".format(vf.bitrate / 1e6)
                    vf.bitrate > 0 -> "%.0f Kbps".format(vf.bitrate / 1e3)
                    else -> ""
                }
            }
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
    BackHandler { if (fs && fsEpPanel) fsEpPanel = false else if (fs) exitFs() else onBack() } // 分层返回：面板→关面板→退全屏→回墙
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
        // 选集看板打开时方向键=网格走焦（2 列：左右±1/上下±2），OK=选中即播并收看板，Esc/Back=收（用户 2026-11-04 遥控要求）
        if (fsEpPanel) {
            val nEp = lines().getOrNull(line)?.list?.size ?: 0
            fun mvEp(d: Int) { fsFocusEp = (fsFocusEp + d).coerceIn(0, (nEp - 1).coerceAtLeast(0)) }
            return@onPreviewKeyEvent when (ev.key) {
                // 用户 2026-11-04：看板未选集直接按返回=只收看板回全屏播放界面（不退全屏）
                Key.Escape, Key.Back -> { fsEpPanel = false; pokeFs(); true }
                Key.DirectionLeft -> { mvEp(-1); true }
                Key.DirectionRight -> { mvEp(1); true }
                Key.DirectionUp -> { mvEp(-2); true }
                Key.DirectionDown -> { mvEp(2); true }
                Key.Enter, Key.DirectionCenter -> { if (nEp > 0) goEpTo(fsFocusEp); fsEpPanel = false; true }
                else -> false
            }
        }
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
            // 用户 2026-11-04 TVBox 对齐尺寸反推：右侧详情列高度钉死=视频窗高（按钮行不得超到窗口底部之下）；
            // 选集分页 epPageSize 按视口剩余高度（总高-窗高-线路行-分页tab行）估算，保证翻页后视频窗仍在屏内免上翻
            val viewW = maxWidth - 32.dp
            // 用户 2026-11-05 终调：播放器缩至 35%（46%→40%→35 逐步要求再小），文字列 65%——
            // 视频窗高度随 16:9 变矮，下半选集区（weight(1f) 剩余高度）多出更多行显示集数按钮
            val vidW = if (wide) (viewW - 16.dp) * 0.35f else viewW
            val vidH = vidW * 9f / 16f
            // 集数钮列数不写死：随集名长度自适应（下方选集区按 epSub 计算，用户 2026-11-05）
            // 用户 2026-11-05 照截图：页内装不下的在选集区内部上下滚，上半部分（视频/详情/线路/tab 行）位置不动
            // 用户 2026-11-05 紧凑化：每页 20→40，配合播放器/文字缩号后一屏可见更多集数
            val epPageSize = 40
            var epPage by remember(line, itemS) { mutableStateOf(0) }
            LaunchedEffect(curEp, epPageSize) { epPage = curEp / epPageSize } // 当前集换页跟读（连播翻页可见）
            // 用户 2026-11-05：整页不再滚动（上半固定），滚动只发生在选集区内部
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 顶行整体撤销（用户 2026-11-03：返回/快搜均并入详情按钮行，视频上方不再遮挡）
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
                        modifier = (if (wide) Modifier.weight(35f) else Modifier.fillMaxWidth())
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                            .focusable()
                            .focusRequester(videoFsReq)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown &&
                                    (ev.key == Key.Enter || ev.key == Key.DirectionCenter)
                                ) { enterFs(); true } else false
                            },
                    )
                    Column(
                        // 用户 2026-11-04 反馈：列内滚动会把按钮行滚到窗口底裁切、被线路行遮挡
                        // → 取消滚动，文本逐行限宽加省略号，按钮永远完整可见；全文走「📖 简介」弹窗
                        modifier = if (wide) Modifier.weight(65f).height(vidH)
                        else Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AdultTag(adultOf(itemS))
                            // 用户 2026-11-05 照截图：标题保持大号（截图≈50px 特大），正文降一号换行多显
                            Text(detail?.title?.ifBlank { null } ?: itemS.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text("· ${itemS.sourceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                        }
                        if (metaLine.isNotBlank()) Text(metaLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        detail?.director?.ifBlank { null }?.let { Text("导演：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        detail?.actor?.ifBlank { null }?.let { Text("主演：${it.take(100)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        // 用户 2026-11-05 终稿：简介吃列内剩余高度（weight），按钮行非权重最后测量——
                        // 视频窗再缩简介先被压缩，按钮行永远完整可见（上一版整行移出列，用户要求放回详情列内）
                        descFull?.let { d ->
                            Box(modifier = if (wide) Modifier.weight(1f) else Modifier, contentAlignment = Alignment.TopStart) {
                                Text(
                                    d.take(300) + if (d.length > 300) "…" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    maxLines = 8, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } ?: run { if (wide) Spacer(modifier = Modifier.weight(1f)) }
                        Text(
                            listOfNotNull("正在播放：${itemS.name}", epName()?.let { "· $it" }, if (buffering) "缓冲中…" else null).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (buffering) Color.Yellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        errText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        errorHint?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        // 按钮行（用户 2026-11-05 定稿：放回详情列内末尾+横向可滑；列内简介 weight 吸收高度收缩，
                        // 按钮行永远完整可见——视频窗再小也不丢按钮）
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PillButton(onClick = onBack) { Text("返回", style = MaterialTheme.typography.labelMedium) }
                            PillButton(onClick = { kept = keepSt.toggle(itemS) }) { Text(if (kept) "已收藏" else "收藏", style = MaterialTheme.typography.labelMedium) }
                            PillButton(onClick = {
                                LineSpeed.delete(LineSpeed.key(itemS.sourceKey, itemS.vodId, curEp))
                                userPickedLine = false
                                speeds = null
                                probeSpeeds(curEp)
                                toast("重新测速各线路…")
                            }) { Text("测速", style = MaterialTheme.typography.labelMedium) }
                            PillButton(onClick = { showDesc = true }) { Text("简介", style = MaterialTheme.typography.labelMedium) }
                            // 用户 2026-11-05：换源/搜关联合并后定名「快搜」——点开即换源海报网格+关联词（点词切全源搜）
                            PillButton(onClick = { chDlg = true }) { Text("快搜", style = MaterialTheme.typography.labelMedium) }
                            // 用户 2026-11-03 定稿：非全屏不放上下集钮（遥控走选集行点选；全屏动作行内有 ⏮/⏭）
                        }
                    }
                }
                // 线路行（web flagRow：flag(集数)+测速标签第二行；单线判慢给 swapHint 出口）
                // 行内换源结果组已撤（用户 2026-11-03：换源改弹框，同名命中+测速在框内逐源增量呈现）
                val ls = lines()
                if (ls.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("线路(共 ${ls.size} 条)", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ls.forEachIndexed { i, lg ->
                                // 用户 2026-11-05 终改：线路药丸统一走详情按钮同款 chip（PillButton）——
                                // 白20%/选中白40%、无框无红条；行内=源名+分辨率(绿)+·+测速状态词
                                val sel = i == line
                                val res = resOf(lg.flag)
                                val nm = (res?.let { lg.flag.replace(it, "", ignoreCase = true) } ?: lg.flag)
                                    .trim().trim('-', '_', ' ').ifEmpty { lg.flag }
                                val tg = tagFor(i)
                                PillButton(onClick = { if (!sel) switchLine(i) }, selected = sel) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            "$nm(${lg.list.size})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        if (res != null) Text(
                                            res, style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF81C784),
                                        )
                                        if (tg != null) {
                                            Text("·", style = MaterialTheme.typography.labelSmall,
                                                color = Color(0x99FFFFFF))
                                            Text(tg.label, style = MaterialTheme.typography.labelSmall,
                                                color = speedTagColor(tg.cls))
                                        }
                                    }
                                }
                            }
                            if (singleSlow) {
                                PillButton(onClick = { chDlg = true }) { Text("⚡ 该源仅此1条线 · 换源", maxLines = 1, style = MaterialTheme.typography.labelMedium) } // 改开换源弹框（同名+测速直达）
                            }
                        }
                    }
                    // 用户 2026-11-05：线路与选集间加横线分隔
                    HorizontalDivider(thickness = 1.dp, color = Color(0x33FFFFFF))
                    // 选集行（web epRow：当前集 on + 续播位 prog 红点）
                    // 用户 2026-11-04：集数超一页加 tab 分页，页大小=视口可容纳估算值，保证翻页后视频窗仍完整在屏内
                    val eps = g?.list ?: emptyList()
                    val showTabs = eps.size > epPageSize
                    val pageMax = ((eps.size - 1) / epPageSize).coerceAtLeast(0)
                    val curPage = if (showTabs) epPage.coerceIn(0, pageMax) else 0
                    val epStart = curPage * epPageSize
                    val epSub = eps.subList(epStart, minOf(epStart + epPageSize, eps.size))
                    // 用户 2026-11-05 终定：配色样式维持 chip，但宽度改弹性自适应（撤固定 epBtnW）——
                    // chip 按集名全文撑宽、FlowRow 自动换行，任何集名都完整显示不截字
                    // 用户 2026-11-05 照截图：选集区全宽（撤「选集」侧标签），
                    // 占满视口剩余高度、内部上下滚，tab 行以上不动
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showTabs) {
                            val tabRange = { pg: Int -> "${pg * epPageSize + 1}-${minOf((pg + 1) * epPageSize, eps.size)}" }
                            // 用户 2026-11-05：tab 统一走详情按钮同款 chip（白20%/当前页白40%、无框）
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0..pageMax).forEach { pg ->
                                    PillButton(onClick = { epPage = pg }, selected = pg == curPage) {
                                        Text(tabRange(pg), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                epSub.forEachIndexed { j, e ->
                                    val i = epStart + j
                                    // 用户 2026-11-05：去掉 web epRow 续播位红点徽标（明示不需要，有意偏离蓝本）
                                    PillButton(
                                        // 用户 2026-11-04：点选后焦点回播放窗，再按遥控 OK 即进全屏
                                        onClick = { goEpTo(i); scope.launch { withFrameNanos { }; runCatching { videoFsReq.requestFocus() } } },
                                        selected = i == curEp,
                                    ) { Text(e.name, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
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
            // ===== TVBox 手机版样式复刻（用户 2026-11-04 参考图）：左上标题/分辨率·右上码率·右中当前大时间+剩余·红细线进度·底部图标动作行；跟手拖拽+遥控焦点均可用 =====
            val fsTitle = (detail?.title?.ifBlank { null } ?: itemS.name) + "：第${curEp + 1}集"
            val shownMs = if (fsDragPos >= 0) fsDragPos else fsPos
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).padding(20.dp, 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(width = 4.dp, height = 22.dp).background(Color(0xFFE53935)))
                        Text(fsTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    if (fsResTxt.isNotEmpty()) Text(fsResTxt, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                }
            }
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(20.dp, 14.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (fsBit.isNotEmpty()) Text(fsBit, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    if (buffering) Text("缓冲中…", color = Color.Yellow, style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtHms(shownMs / 1000), color = Color.White, style = MaterialTheme.typography.displaySmall)
                    Text("影片结束 " + if (fsDur > 0) fmtHms((fsDur - shownMs).coerceAtLeast(0) / 1000) else "--:--:--", color = Color(0xBBFFFFFF), style = MaterialTheme.typography.bodyMedium)
                }
            }
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 进度行（参考图样式）：当前时间—红细线+圆点拇指—总时长；手指按住即拖（跟手预览、松手 seek）、单击定位；遥控 ←→ 仍走 ±15s（fsKeyMod）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(fmtHms(shownMs / 1000), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier.weight(1f).height(24.dp).pointerInput(fsDur) {
                                awaitEachGesture {
                                    val downEv = awaitFirstDown(requireUnconsumed = false)
                                    val w = size.width.toFloat()
                                    fun fracPos(x: Float): Long = (x / w).coerceIn(0f, 1f).let { (it * fsDur).toLong() }
                                    fsDragPos = fracPos(downEv.position.x)
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Main)
                                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                        fsDragPos = fracPos(ch.position.x)
                                        ev.changes.forEach { it.consume() }
                                    }
                                    if (fsDur > 0) player.seekTo(fsDragPos.coerceAtLeast(0))
                                    fsDragPos = -1L
                                    pokeFs()
                                }
                            },
                        ) {
                            Canvas(Modifier.matchParentSize()) {
                                val half = size.height / 2f
                                val f = if (fsDur > 0L) (shownMs.toFloat() / fsDur).coerceIn(0f, 1f) else 0f
                                drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, half), Offset(size.width, half), 3.dp.toPx())
                                drawLine(Color(0xFFE53935), Offset(0f, half), Offset(size.width * f, half), 5.dp.toPx())
                                drawCircle(Color(0xFFE53935), 8.dp.toPx(), Offset(size.width * f, half))
                            }
                        }
                        Text(if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
                        // 走查 #13：±15s 提示与进度同行（1.5s 自隐）
                        seekHint?.let { Text(it, color = Color(0xFFFFE082), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                    // 动作行（参考图图标行映射本 app 能力；「▤ 选集」=全屏任意选集，遥控十字键可逐钮聚焦）
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 用户 2026-11-05：上下集高频钮前置到播放键之前并改文字形式；
                        // 遥控 OK 直接触发播放/暂停（fsKeyMod Enter/DirectionCenter 已接，无需聚焦本钮）
                        FsAct("上集", "上一集", off = curEp <= 0) { goEp(-1) }
                        FsAct("下集", "下一集", off = total > 0 && curEp >= total - 1) { goEp(1) }
                        // 用户 2026-11-04：暂停态显示红色播放键，其余统一白色字形
                        // U+23F8 在 Android emoji 字体下无视 VS15 恒渲橙色，改用文字双竖条 ❚❚
                        FsAct(if (playing) "❚❚" else "▶", if (playing) "暂停" else "播放",
                            tint = if (playing) null else Color(0xFFE53935)) { player.playWhenReady = !player.playWhenReady }
                        FsAct("↺", "重播") { replayHere() }
                        FsAct("⟳", if (repeatOne) "循环开" else "循环关", active = repeatOne) {
                            repeatOne = !repeatOne
                            toast(if (repeatOne) "已开启单集循环" else "已关闭单集循环")
                        }
                        FsAct("⏩", "倍速×$speedX", active = speedX != 1f) { cycleSpeed() }
                        FsAct("⛶", if (zoomMode) "满屏" else "适配") { toggleScale() }
                        FsAct("⌂", "重置") { resetView() }
                        FsAct("▤", "选集", active = fsEpPanel) { fsEpPanel = !fsEpPanel; pokeFs() }
                        FsAct("✕", "退出全屏") { exitFs() }
                    }
                }
            }
            // 全屏选集看板（用户 2026-11-04 追加：改右侧半透明竖版，可遥控聚焦选择）：顶部线路行 + 整高集数网格；
            // 点选/OK 即播并收看板；打开时自动抢焦点，十字键即可走焦；BACK/Esc=关看板
            AnimatedVisibility(visible = fsEpPanel, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Column(
                    modifier = Modifier.width(300.dp).fillMaxHeight().background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        lines().forEachIndexed { i, lg ->
                            TextButton(onClick = { if (i != line) switchLine(i) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("${lg.flag}(${lg.list.size})", color = if (i == line) Color(0xFFFFEB3B) else Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    val epsFs = lines().getOrNull(line)?.list ?: emptyList()
                    LazyVerticalGrid(
                        state = fsGrid,
                        columns = GridCells.Adaptive(92.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(epsFs) { i, e ->
                            Button(
                                onClick = { goEpTo(i); fsEpPanel = false },
                                modifier = if (i == fsFocusEp) Modifier.border(2.dp, Color(0xFFFFEB3B), RoundedCornerShape(8.dp)) else Modifier,
                                shape = RoundedCornerShape(8.dp), // 用户 2026-11-05：形状与全 app 统一（板上配色保留）
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        i == curEp -> MaterialTheme.colorScheme.primary
                                        i == fsFocusEp -> Color(0xFF1565C0)
                                        else -> Color(0xFF3A3A3A)
                                    },
                                    contentColor = if (i == curEp) MaterialTheme.colorScheme.onPrimary else Color.White,
                                ),
                            ) { Text(e.name, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            // 看板遥控焦点维护：打开=对齐当前集；移动=滚动保证可见
            LaunchedEffect(fsEpPanel) {
                if (fsEpPanel) {
                    fsFocusEp = curEp
                    runCatching { fsGrid.scrollToItem(curEp) }
                }
            }
            LaunchedEffect(fsFocusEp) { // 仅当焦点跑出可视区才滚动，避免每按一次都整屏跳动
                if (fsEpPanel && fsGrid.layoutInfo.visibleItemsInfo.none { it.index == fsFocusEp })
                    runCatching { fsGrid.scrollToItem(fsFocusEp) }
            }
        }
    }

    // 快搜弹框（用户 2026-11-05：原「换源」「搜关联」两钮两框合并后定名「快搜」）：默认逐源同名海报网格+⚡测速，
    // 框内同时显示关联词，点词即转全源搜索；点海报跨源续播；BACK 关框回详情页
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
    properties = tmDialogProps,
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
        confirmButton = { TmBtn(onClick = { showDesc = false }) { Text("关闭") } },
    )
}
