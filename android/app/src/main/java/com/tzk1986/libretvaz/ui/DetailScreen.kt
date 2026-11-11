package com.tzk1986.libretvaz.ui

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.tzk1986.libretvaz.R
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.LineSpeed
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.ui.theme.TvBtnKind
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.ui.theme.tvBtnContainer
import com.tzk1986.libretvaz.ui.theme.tvEpTextColor
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
    // 按 TVBox 实拍图零填充（05:46 / 42:07、00:42:07）——用户 2026-11-10
    return if (h > 0) "%02d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

/**
 * TVBox 对标 C10（2026-11-10）：点播 PlayerView 两档构造。media3 1.11.1 无 setUseSurfaceView
 * （javap 实锤，表面类型仅 XML attr surface_type 可定），TextureView 档走 res/layout/pv_texture.xml inflate。
 */
private fun pvFor(ctx: android.content.Context, texture: Boolean): PlayerView =
    if (texture) android.view.LayoutInflater.from(ctx).inflate(R.layout.pv_texture, null) as PlayerView
    else PlayerView(ctx)

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
    // TVBox 复刻：焦点=白描边+40%红底、按下=同40%红、常态深灰；选中不改底色（指示=文字下红划线）。
    // 用户 2026-11-09 装机像素实证 FilterChip 底/描边分层（描边在 48dp 触控盒、底色内容尺寸）
    // 描边必比按钮大一圈——与 TmBtn 同方案改 Box 同节点自绘；requiredHeight(32) 保行内等高规范
    var focused by remember { mutableStateOf(false) }
    val pillSrc = remember { MutableInteractionSource() }
    val pillPressed by pillSrc.collectIsPressedAsState()
    val pillShape = RoundedCornerShape(50)
    // 投影适配（用户 2026-11-11）：高/内边距改 tvu 等比——与 tvSp 字号同比例律，
    // 按钮宽占列宽比不随 dpi 变（固定 dp 内边距+等比字号在低 dpi 大屏溢出裁切实锤）
    Box(
        modifier = modifier
            .requiredHeight(tvu(32))
            .onFocusEvent { focused = it.isFocused }
            .background(tvBtnContainer(focused, pillPressed, TvBtnKind.Detail), pillShape)
            .then(if (focused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, pillShape) else Modifier)
            .clickable(interactionSource = pillSrc, onClick = onClick)
            .padding(horizontal = tvu(10), vertical = tvu(2)),
        contentAlignment = Alignment.Center,
    ) {
        // 选中红下划线（用户 2026-11-06）：不占布局、置文字底缘 5px
        Column(
            modifier = if (selected) Modifier.drawBehind {
                val y = size.height + 3.2.dp.toPx()
                drawLine(TvColors.Red, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 3.dp.toPx(), cap = StrokeCap.Round)
            } else Modifier,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color.White,
                ) { content() }
            }
        }
    }
}

/** 全屏动作行图标钮（TVBox 复刻）：off=半透明禁用，active=主题红高亮（循环/倍速/选集开启态）；
 *  图标统一追加 U+FE0E 强制文字字形，防止 ⏸/ 等被系统 emoji 字体渲染成橙色色块（用户 2026-11-04 反馈配色不一致） */
@Composable
private fun FsAct(
    icon: String,
    desc: String,
    off: Boolean = false,
    active: Boolean = false,
    tint: Color? = null,
    res: Int = 0,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    // TVBox 复刻（用户 2026-11-09）：焦点态=白描边+40%红底；按下=同40%红无框（button_dialog_vod selector 双分支）
    var focused by remember { mutableStateOf(false) }
    val actSrc = remember { MutableInteractionSource() }
    val actPressed by actSrc.collectIsPressedAsState()
    TextButton(
        onClick = onClick,
        enabled = !off,
        interactionSource = actSrc,
        contentPadding = PaddingValues(horizontal = tvu(4), vertical = 0.dp),
        // M3 1.4.0 无 focusedContainerColor：焦点亮底折进 containerColor
        colors = ButtonDefaults.textButtonColors(
            // TVBox 复刻：焦点/按下→40%红底，未激活全屏透明（button_dialog_vod）
            containerColor = tvBtnContainer(focused, actPressed, TvBtnKind.Fs),
        ),
        // 紧凑修订（用户 2026-11-10 手机实拍）：覆掉 M3 48dp 最小触控高的留白；
        // 投影适配（2026-11-11）高改 tvu(40) 等比，随短边 dp 与字号同律
        modifier = modifier.height(tvu(40)).semantics { contentDescription = desc }
            .onFocusEvent { focused = it.isFocused; onFocused(it.isFocused) }
            .then(if (focused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(TvColors.RadiusFs)) else Modifier),
    ) {
        // 用户 2026-11-09 #134：res≠0 用 TVBox 解码矢量图标（vs_30 大小），文字钮保留给跳过/倍速等文本胶囊
        val fg = when {
            off -> Color(0x66FFFFFF)
            tint != null -> tint
            active -> TvColors.Red
            else -> Color.White
        }
        if (res != 0) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(res),
                contentDescription = desc,
                tint = fg,
                modifier = Modifier.size(tvu(26)),
            )
        } else {
            Text(icon + "︎", fontSize = tvSp(18), color = fg)
        }
    }
}

/** 全屏右上暂停标（用户 2026-11-10 按 TVBox 实拍图）：仅暂停时由调用方挂载——
 *  「当前 / 总时长」文字在前、圆圈双竖条暂停图标在后（点按续播），短细进度线（红前白后）仅挂在时间文字下方。
 *  控件亮/自隐两处共用；toggle=null 时图标不可点。 */
@Composable
private fun FsPauseBadge(timeTxt: String, frac: Float, toggle: (() -> Unit)?) {
    // 用户 2026-11-10 修订：短进度线只挂在时间下方，不越过圆圈图标
    // 用户 2026-11-10 修订：进度线宽度=时间文字实测宽（onTextLayout），红色填充按 frac 等比，
    // 不再固定 110dp——避免短于/长于文字看着不协调
    val dens = LocalDensity.current
    var textW by remember { mutableStateOf(0.dp) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                timeTxt, color = Color.White, style = MaterialTheme.typography.titleMedium,
                onTextLayout = { r -> textW = with(dens) { r.size.width.toDp() } },
            )
            Box(Modifier.width(textW).height(3.dp).background(TvColors.Track, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(TvColors.Red, RoundedCornerShape(2.dp)))
            }
        }
        val m = Modifier.size(32.dp)
        Icon(
            painterResource(R.drawable.tvb_v_pause_circle), "已暂停", tint = Color.White,
            modifier = if (toggle != null) m.clickable { toggle() } else m,
        )
    }
}


/** 选集网格 cell（TVBox item_series 精准复刻）：常态 SeriesBg 45% 深灰、圆角 vs_10、高 vs_50、ts_20；
 *  焦点/按下→40%红（focused 另白描边）；当前集=主题红字（EpisodeAdapter selected），无下划线
 *  （红下划线仅源/分组 flag chip） */
@Composable
private fun EpCell(
    current: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    // 用户 2026-11-09：改 Box 同节点自绘（Button 48dp 触控盒致描边比底色大一圈，同 TmBtn）
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(tvu(10))
    Box(
        modifier = modifier
            .height(tvu(50))
            .onFocusEvent { focused = it.isFocused }
            .background(tvBtnContainer(focused, pressed, TvBtnKind.Series), shape)
            .then(if (focused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, shape) else Modifier)
            .clickable(interactionSource = src, onClick = onClick)
            .padding(horizontal = tvu(10)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = tvSp(20), color = tvEpTextColor(current))
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
    var subOn by remember { mutableStateOf(false) } // P1-6：当前是否有字幕轨开启（钮高亮）
    // C1 修复：初值改 false——播放器未 load 时不该显「缓冲中」（旧初值 true 是"进页即起播"时代的加载反馈，
    // 预览关待播态下 player 永不发事件，文案恒挂=误判成"关预览仍起播"）
    var buffering by remember { mutableStateOf(false) }
    var errorHint by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(true) }
    // TVBox 对标 C1（2026-11-10）：窗口预览关=进详情 paint 不 load，窗显海报+常驻播放钮；
    // pendSec/pendProg 存 paint 算好的续播位，点播放/窗OK 才起播（render 链一行不改、仅时机后移）
    var noPreview by remember { mutableStateOf(false) }
    var pendSec by remember { mutableLongStateOf(0L) }
    var pendProg by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(keepSt.contains(item)) }
    var changing by remember { mutableStateOf(false) } // auto 换源链路进行中标记
    // 用户 2026-11-05：换源与搜关联合并为单弹窗单钮，定名「快搜」——chDlg 即入口，框内默认换源海报网格+关联词，点词转全源搜
    var chDlg by remember { mutableStateOf(false) }
    var fs by remember { mutableStateOf(false) }
    var fsBar by remember { mutableStateOf(true) }
    var fsSeq by remember { mutableIntStateOf(0) }
    // TVBox 式动作行状态（2026-11-03 定稿清单：拖拽进度/重播/单集循环/倍速/画面比例/重置）
    var fsPos by remember { mutableLongStateOf(0L) }
    // A5（审计 2026-11-11）：片头/片尾自动跳每集消费一次（记下标；换集/换线重置自然生效）
    var skipInDone by remember { mutableStateOf(-1) }
    var skipOutDone by remember { mutableStateOf(-1) }
    var fsDur by remember { mutableLongStateOf(0L) }
    // TVBox 对标 C4（2026-11-10）：初始态跟随全局档 zoom_pref（1=满屏为 true；2=拉伸时钮显「适配」态）
    var zoomMode by remember { mutableStateOf(zoomModeInitial(settings.zoomPref)) }
    var repeatOne by remember { mutableStateOf(false) }
    var fsPv by remember { mutableStateOf<PlayerView?>(null) }
    // TVBox 对标 F1（2026-11-10）：倍速——speedX 跨换集/换线保留、退全屏复位 1.0（§8-R2）；speedPanel 弹层；speedFocus 面板走焦下标
    var speedX by remember { mutableStateOf(1f) }
    var speedPanel by remember { mutableStateOf(false) }
    var speedFocus by remember { mutableIntStateOf(0) }
    // 用户 2026-11-04：TVBox 复刻三件套状态——选集面板 / 顶部分辨率·码率 / 拖拽跟手临时位（-1=未拖）
    var fsEpPanel by remember { mutableStateOf(false) }
    // 看板遥控焦点：自持下标（根节点已占 focusReq，不依赖 Compose 焦点系统；2 列网格，上下=±2）
    var fsFocusEp by remember { mutableIntStateOf(0) }
    val fsGrid = rememberLazyGridState()
    // 用户 2026-11-04：非全屏播放窗可聚焦，遥控 OK=进全屏；选集点选后焦点自动回窗口
    // （2026-11-09 probe6：窗焦点载体改 view 级 PlayerView，见 !fs 块内 Ruling）
    // TVBox showBottom 落焦语义（用户 2026-11-09 需求 d）：呼出控件=焦点进动作行首个钮；
    // 焦点若锚在满屏根节点，←→ 焦点搜索无右邻=按钮行无法移焦
    val fsActFr = remember { FocusRequester() }
    // OK 双消费修复的补丁（用户 2026-11-10 走查实证两轮）：触屏 poke 呼出时钮焦点必空（Compose touch mode
    // 拒焦，requestFocus 6连false 实锤；且 adb 不翻 View.isInTouchMode，用它判据在模拟器恒放行落空）。
    // 判据=钮行引用计数：钮含焦+1、失焦−1，>0 才放行交钮；计数=0 时 OK 在 fsKeyMod 单次切播放。
    val fsRowFocusN = remember { mutableIntStateOf(0) }
    var fsResTxt by remember { mutableStateOf("") }
    var fsQualityTxt by remember { mutableStateOf("") }
    var fsBit by remember { mutableStateOf("") }
    var fsBuf by remember { mutableLongStateOf(0L) }          // TVBox 次缓冲进度（bufferedPosition）
    var fsClock by remember { mutableStateOf("") }            // 底栏右上时钟 HH:MM
    var avZone by remember { mutableStateOf(EdgeZone.Volume) }   // 竖拖半区（左亮度/右音量）
    var avLevel by remember { mutableFloatStateOf(0f) }
    var avVisible by remember { mutableStateOf(false) }
    var avJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var locked by remember { mutableStateOf(false) }   // TVBox 锁屏：锁定后屏蔽全部触屏/遥控交互，仅解锁钮与返回可用
    // 用户 2026-11-10 复刻 TVBox 按钮行「硬/软」：codec 选择定死在 renderer 创建期（1.11.1 javap 实锤
    // MediaCodecSelector.DEFAULT/PREFER_SOFTWARE），切换=重建播放器（remember key）+ 原位续播
    // TVBox 对标 F2+C6（2026-11-10）：播放器引擎两档——0=Exo 默认全链/1=系统 MediaPlayer 轻量壳；
    // 进页读档、动作行「Exo/系统」胶囊当场切（settings 持久化，重建续位复用 hwResume 模板）
    var engineX by remember { mutableIntStateOf(settings.playerEngine) }
    var hwDecode by remember { mutableStateOf(true) }
    var hwResume by remember { mutableStateOf<Pair<Long, Boolean>?>(null) } // (位置ms, 切换前是否在播)，LaunchedEffect(hwDecode, engineX) 消费
    var fsEndTxt by remember { mutableStateOf("") }   // 右下时钟区「预计结束」HH:MM:SS（1s 循环随 fsClock 更新）
    var fsDragPos by remember { mutableLongStateOf(-1L) }
    val focusReq = remember { FocusRequester() }
    val insetCtrl = remember {
        (context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
    }

    // TVBox 对标 F2+C6：引擎两档工厂——系统档=SysCtl 壳（无 Exo 独有能力，UI 降级显）；Exo 档透传行为逐 API 等价旧链
    val player: PlayCtl = remember(hwDecode, engineX) {
        if (engineX == 1) SysCtl(context) else ExoCtl(buildPlayer(context, hwDecode = hwDecode))
    }
    fun lines(): List<PlayLine> = detail?.lines ?: emptyList()
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    fun linkOf(l: Int, e: Int): String? = lines().getOrNull(l)?.list?.getOrNull(e)?.link
    fun playAt(l: Int, e: Int, startPosMs: Long = 0) {
        val u = linkOf(l, e) ?: return
        errorHint = null
        buffering = true
        noPreview = false // 任何真实 load 即撤海报待播态（C1）
        player.load(u, startPosMs)
        // 下一集预取+带宽余量闸（用户 2026-11-06 批准；实机复测发现慢线预取抢当前播放带宽反噬换集速度）：
        // 仅当当前线实测吞吐 ≥ 2×节目码率（A4）才预取；未测/判死/码率未知一律跳过，绝不与播放抢流。
        // T-C1：预取走 Exo 的 VideoCache 本地代理，系统档不命中该缓存=无收益，跳过
        // P1-1（审计 2026-11-11）：LineSpeed.cached 盘回灌（miss 时读文件+JSON）移出主线程
        linkOf(l, e + 1)?.let { next ->
            val lk = LineSpeed.key(itemS.sourceKey, itemS.vodId, e)
            scope.launch(Dispatchers.IO) {
                val kbps = LineSpeed.cached(lk)?.getOrNull(l)
                val bps = LineSpeed.cachedBps(lk)?.getOrNull(l)
                if (player.exo != null && kbps != null && kbps > 0 && bps != null && bps > 0 && kbps * 1024 >= 2 * bps) {
                    VodPreload.preload(context, next)
                }
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
        // TVBox 对标 F1 §8-R2：退全屏复位倍速，防误触残留
        speedPanel = false
        speedX = 1f
        // 审计 2026-11-11 P0-4：退全屏必解「锁屏」——locked 的解锁钮只在全屏层且方向键被吞，
        // 残留 locked 会让再进全屏时遥控彻底锁死（只能杀进程），Back 逃生口后立即复位是唯一可靠兜底
        locked = false
        // 修复（用户 2026-11-05：退全屏后状态栏永久压住顶部 tab）：全局策略状态栏常隐，
        // 这里只恢复导航栏；systemBars() 会把 hide 标志一并清掉导致状态栏再也回不去
        insetCtrl?.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
    }
    fun pokeFs() { fsBar = true; fsSeq++ }
    // OK 双发互消止损（用户 2026-11-10 走查实证）：初始焦点自动落播放钮描边，触屏 poke 后按 OK，
    // 放行路径交钮 onClick 与 fsKeyMod 兜底各 toggle 一次=净零（gg1/gg3 实锤 playing→playing）。
    // 统一走此去抖 toggle：400ms 内重复请求只生效一次，各入口（按键/单击/大钮/右图标）共用。
    var lastToggleAt by remember { mutableLongStateOf(0L) }
    fun togglePlayFs() {
        val now = System.currentTimeMillis()
        if (now - lastToggleAt < 400) return
        lastToggleAt = now
        player.playing = !player.playing
    }
    // TVBox 复刻（用户 2026-11-09）：全屏 ←→=长按连续快进预览（松手才真 seek）+ 居中时间胶囊；
    // slideTick 心跳：每步进 ++，胶囊显 1s 尾巴后自隐（updateSeekUI 消息 1000/1001 等价）
    val slide = remember { SlideSeek() }
    var slideTick by remember { mutableIntStateOf(0) }
    var slideShow by remember { mutableStateOf(false) }
    var slideFinal by remember { mutableLongStateOf(0L) }  // 松手提交后胶囊仍显示的目标位
    var slideDirFinal by remember { mutableIntStateOf(1) }
    LaunchedEffect(slideTick) {
        if (slideTick > 0) { slideShow = true; delay(1000); slideShow = false }
    }
    // 窗口模式 ±15s 步进（全屏改走 SlideSeek）：toast 反馈
    fun seekBy(deltaSec: Long) {
        val dur = player.duration
        if (dur <= 0) { toast("快进/快退暂不可用（流未就绪）"); return }
        val pos = (player.position / 1000 + deltaSec).coerceIn(0, (dur / 1000) - 1)
        player.seekTo(pos * 1000)
        toast((if (deltaSec < 0) "« 15s " else "15s » ") + fmtHms(pos) + " / " + fmtHms(dur / 1000))
    }

    // ===== 进度持久化（web fongmi_prog：5s 节流/pause 即写/ended 清/孤儿随写清理） =====
    fun writeProg(sec: Long, durSec: Long) {
        if (player.state == SysCore.ST_END) return
        val g = lines().getOrNull(line) ?: return
        val p = Prog(line, g.flag, curEp, sec, durSec, System.currentTimeMillis())
        prog.save(itemS.vodId, itemS.sourceKey, p, ProgStore.aliveKeys(context))
    }

    // ===== TVBox 式动作行（用户 2026-11-10 实拍图裁决：仅 7 钮；倍速/跳过/重置/循环/选集/退出撤 UI，逻辑各归其位） =====
    fun replayHere() { errorHint = null; playAt(line, curEp); toast("重新播放本集") }
    fun toggleScale() {
        zoomMode = !zoomMode
        fsPv?.resizeMode = if (zoomMode) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        toast(if (zoomMode) "画面：满屏裁切" else "画面：比例适配")
    }
    // 双击三分区左右=±15s：seekTo 后借 slideFinal/slideTick 复用居中时间胶囊反馈（同长按快进样式）
    fun doubleSeek(dir: Int) {
        val dur = player.duration
        if (dur <= 0) { toast("快进/快退暂不可用（流未就绪）"); return }
        val t = (player.position + dir * 15_000L).coerceIn(0L, (dur - 1000L).coerceAtLeast(0L))
        player.seekTo(t)
        slideFinal = t; slideDirFinal = dir; slideTick++
    }
    // TVBox 锁屏复刻：锁定收起全部浮层；解锁恢复控件呼出
    fun toggleLock() {
        locked = !locked
        if (locked) { fsBar = false; fsEpPanel = false } else pokeFs()
    }
    // 用户 2026-11-10 复刻 TVBox「硬解/软解」钮：先记续位再翻转 hwDecode；player 随 remember key 重建，
    // 重建后的 DisposableEffect(player)/LaunchedEffect(player…) 自动换绑，本 effect 负责原位续播
    fun toggleHwDecode() {
        if (buffering) { toast("缓冲中，稍后再试"); return }
        hwResume = player.position to player.playing
        hwDecode = !hwDecode
        toast(if (hwDecode) "已切硬解" else "已切软解")
    }
    LaunchedEffect(hwDecode, engineX) {
        val r = hwResume ?: return@LaunchedEffect // 首次组帧 hwResume=null 自然跳过
        hwResume = null
        playAt(line, curEp, r.first)
        player.playing = r.second
    }
    // TVBox 对标 F2+C6：动作行「Exo/系统」胶囊——切换=重建 player（remember key）+原位续播，复用 hwResume 模板
    fun toggleEngine() {
        if (buffering) { toast("缓冲中，稍后再试"); return }
        hwResume = player.position to player.playing
        engineX = if (engineX == 1) 0 else 1
        settings.playerEngine = engineX
        toast(if (engineX == 1) "已切系统播放器（音轨/软硬解/码率项降级）" else "已切 Exo 播放器")
    }
    // 用户 2026-11-10 复刻 TVBox「音轨」钮：跨音频 group 展平轮切（media3 1.11 新 API：
    // currentTracks.groups + overrides[TrackGroup] + TrackSelectionOverride(TrackGroup, index)）
    fun toggleAudioTrack() {
        // T-C1：音轨切换为 Exo 独有能力，系统档按钮已置灰；防御兜底=不操作
        val ex = player.exo ?: return
        val groups = ex.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSupported }
        val flat = groups.flatMap { gr -> (0 until gr.length).filter { gr.isTrackSupported(it) }.map { idx -> gr to idx } }
        if (flat.size <= 1) {
            toast(if (flat.isEmpty()) "暂无可用音轨" else "该流只有 1 条音轨，无轨可切")
            return
        }
        val overrides = ex.trackSelectionParameters.overrides
        var cur = -1
        flat.forEachIndexed { k, pair ->
            if (cur >= 0) return@forEachIndexed
            val ov = overrides[pair.first.mediaTrackGroup]
            if (ov != null) { if (pair.second in ov.trackIndices) cur = k }
            else if (pair.first.isTrackSelected(pair.second)) cur = k
        }
        val (gr, i) = flat[(cur + 1).mod(flat.size)]
        ex.trackSelectionParameters = ex.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(gr.mediaTrackGroup, i))
            .build()
        val f = gr.getTrackFormat(i)
        val lbl = f.label?.takeIf { it.isNotEmpty() } ?: f.language?.takeIf { it.isNotEmpty() }
        toast("已切音轨 " + (lbl ?: "${i + 1}/${flat.size}"))
    }

    fun toggleSubtitle() {
        // P1-6（审计 2026-11-11）：字幕轨轮切＝对标 TVBox/Emby 标配（旧全项目无文本轨处理）。
        // 档序：关→轨0→…→轨N→关（subNext 纯函数+单测）；关=禁用 TRACK_TYPE_TEXT（清 override 会被
        // 自动选轨按语言重新抢选，禁用才真关）；Exo 独有，系统档钮已置灰。无轨流仅提示不动参数
        val ex = player.exo ?: return
        val flat = ex.currentTracks.groups
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSupported }
            .flatMap { gr -> (0 until gr.length).filter { gr.isTrackSupported(it) }.map { idx -> gr to idx } }
        if (flat.isEmpty()) { toast("该流无字幕轨"); return }
        val p0 = ex.trackSelectionParameters
        val disabled = p0.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
        var cur = -1
        if (!disabled) {
            flat.forEachIndexed { k, pair ->
                if (cur >= 0) return@forEachIndexed
                val ov = p0.overrides[pair.first.mediaTrackGroup]
                if (ov != null) { if (pair.second in ov.trackIndices) cur = k }
                else if (pair.first.isTrackSelected(pair.second)) cur = k
            }
        }
        cur = subNext(cur, flat.size)
        val b = p0.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, cur < 0)
        if (cur >= 0) {
            val (gr, i) = flat[cur]
            b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(gr.mediaTrackGroup, i))
            val f = gr.getTrackFormat(i)
            toast("字幕：${subLabel(f.label, f.language, cur)}")
        } else toast("字幕已关")
        subOn = cur >= 0
        ex.trackSelectionParameters = b.build()
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
            openWith(hits[0], ResumeCtx(line, curEp, player.position / 1000))
        }
    }

    // ===== 测速（B4/H5 复用：LineSpeed.cached 已含 6h 磁盘回灌） =====
    // 崩溃修复（2026-11-04）：LineSpeed.measure 的 playingHealthy 回调跑在 IO 协程，而 ExoPlayer 仅主线程可读
    // （FATAL: Player is accessed on the wrong thread）。IO 侧改读主线程 1s 循环维护的快照。
    val healthySnap = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun playingHealthy() = player.state == SysCore.ST_READY && player.position > 0
    fun tagFor(i: Int): PerfCore.SpeedTag? {
        // C1 待播态不显任何速度读数（含历史缓存回灌）——未起播即无 Mbps（方案 T-B1 验收）
        if (noPreview) return null
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
            val ms = player.position
            line = best; curEp = eIdx; tried = setOf(best)
            toast("已切换到最快线路「${lines()[best].flag}」")
            playAt(best, eIdx, ms)
        }
    }

    // TVBox 对标 C1（2026-11-10）：预览待播态点播放/窗OK=补 paint 后起的整段 render 链再进全屏
    // （声明须在 probeSpeeds 之后——Compose 局部 fun 按语句序可见）
    fun startPending() {
        if (!noPreview) return
        val e = curEp; val sec = pendSec; val prog = pendProg
        playAt(line, e, sec * 1000)
        probeSpeeds(e)
        if (prog && (e > 0 || sec >= 5)) toast("继续播放 第${e + 1}集 ${fmtHms(sec)}")
    }
    fun winActivate() { startPending(); enterFs() }

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
        // B14（审计 2026-11-11）：换线保在播集（序号钳位），不再强制回首集
        val keep = Playback.epMigrate(curEp, lines().getOrNull(i)?.list?.size ?: 0)
        line = i; curEp = keep
        tried = setOf(i)
        userPickedLine = true
        toast("正在切换线路至「${lines()[i].flag}」")
        probeSpeeds(keep)
        playAt(i, keep)
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
        if (!settings.winPreview) {
            // C1：起播链整体后移到用户点播放/窗OK（startPending），线/集/续播位照常先算好
            pendSec = sec; pendProg = fromProg; noPreview = true; buffering = false
            return
        }
        playAt(l, e, sec * 1000)
        probeSpeeds(e)
        when {
            fromProg && (e > 0 || sec >= 5) -> toast("继续播放 第${e + 1}集 ${fmtHms(sec)}")
            r != null && e > 0 -> toast("已在新源第 ${e + 1} 集续播")
        }
    }

    // ===== 播放器监听（T-C1 改走 PlayCtl.Cb 归一回调，Exo/系统两档同一链；注册一次，语义同旧） =====
    val listener = remember(player) {
        object : PlayCtl.Cb {
            override fun onStateChanged(state: Int) {
                when (state) {
                    SysCore.ST_BUF -> buffering = true
                    SysCore.ST_READY -> buffering = false
                    SysCore.ST_END -> {
                        buffering = false
                        prog.clear(itemS.vodId, itemS.sourceKey)
                        if (repeatOne) { player.seekTo(0); player.playing = true; return } // 单集循环：重播本集不连播
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

            override fun onIsPlaying(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying && player.state != SysCore.ST_END && player.position >= 5000) {
                    writeProg(player.position / 1000, (player.duration / 1000).coerceAtLeast(0))
                }
            }

            override fun onError(code: String, detail: String) {
                buffering = false
                if (!code.startsWith("ERROR_CODE_IO") && !code.contains("TIMEOUT")) {
                    com.tzk1986.libretvaz.data.ErrLog.add(
                        "play", "点播 $code: $detail src=${player.currentUri}"
                    )
                }
                if (!settings.autoLine) {
                    errorHint = "播放失败（$code），请换线路或换源"
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
        // P1-1（审计 2026-11-11）：详情盘读下沉 IO（LaunchedEffect 默认 Main，旧=进页主线程读文件+解码）
        val cached = withContext(Dispatchers.IO) { repo.detailCached(source, itemS.vodId) }
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
            fsPos = player.position; fsDur = player.duration.coerceAtLeast(0L)
            fsBuf = player.buffered.coerceAtLeast(0L)
            // 用户 2026-11-10 实拍图：右下大时钟带秒 HH:MM:SS，并推算「影片结束」时刻=当前+剩余
            run {
                val c = java.util.Calendar.getInstance()
                fsClock = fmtClockHms(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
                val e = java.util.Calendar.getInstance()
                e.timeInMillis += (fsDur - fsPos).coerceAtLeast(0L)
                fsEndTxt = fmtClockHms(e.get(java.util.Calendar.HOUR_OF_DAY), e.get(java.util.Calendar.MINUTE), e.get(java.util.Calendar.SECOND))
            }
            healthySnap.set(player.state == SysCore.ST_READY && player.position > 0)
            // A5（审计 2026-11-11）：片头落窗自动跳（每集一次）；片尾过半剩余入窗跳近结尾触发播完链。
            // 滑动预览/拖拽进度中不抢跳（用户意图优先）；非播放态不跳（暂停看片头=不动）
            if (player.isPlaying && !slide.active && fsDragPos < 0) {
                val inJ = skipStartJump(fsPos, fsDur, settings.skipIntroSec)
                if (inJ != null && skipInDone != curEp) { player.seekTo(inJ); skipInDone = curEp }
                val outJ = skipEndJump(fsPos, fsDur, settings.skipOutroSec)
                if (outJ != null && skipOutDone != curEp) { player.seekTo(outJ); skipOutDone = curEp }
            }
            // TVBox 参考样式顶栏数据：分辨率 + 实时码率（1s 与进度同频）
            val vf = player.exo?.videoFormat
            if (vf != null) {
                fsResTxt = "${vf.width} x ${vf.height}"
                fsQualityTxt = qualityLabel(vf.height)
                fsBit = when {
                    vf.bitrate >= 1_000_000 -> "%.2f Mbps".format(vf.bitrate / 1e6)
                    vf.bitrate > 0 -> "%.0f Kbps".format(vf.bitrate / 1e3)
                    else -> ""
                }
            } else if (player.exo == null) {
                // T-C1 系统档：分辨率/清晰度读壳上 video size；码率无实时源=显「—」不伪造（方案 §10-R5）
                if (player.videoW > 0) {
                    fsResTxt = "${player.videoW} x ${player.videoH}"
                    fsQualityTxt = qualityLabel(player.videoH)
                }
                fsBit = "—"
            }
            if (!player.isPlaying || player.duration <= 0 || player.position < 5000) continue
            val n = System.currentTimeMillis()
            if (n - last >= 5000) { last = n; writeProg(player.position / 1000, player.duration / 1000) }
        }
    }

    DisposableEffect(player) {
        player.addCb(listener)
        onDispose {
            if (player.position >= 5000 && player.duration > 0 && player.state != SysCore.ST_END) {
                writeProg(player.position / 1000, player.duration / 1000)
            }
            player.removeCb(listener); player.release()
        }
    }
    // P1-7（审计 2026-11-11）：退后台=暂停（回桌面/切他 app/息屏触发 ON_STOP，播放页无观察=旧后台续响根因）。
    // 两引擎统一走 PlayCtl.playing=false（Exo 弃焦+暂停、Sys 手挂弃焦）；回前台不自动续——「后台续听」
    // 开关需另行确认，本期从简手动复播。键含 player：硬软解/引擎切换重建后观察自动换绑
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP && player.isPlaying) player.playing = false
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 分层返回（用户 2026-11-06 两档定稿）：面板→关面板→控件亮则只隐控件→再按才退全屏→回墙
    BackHandler {
        when {
            fs && speedPanel -> speedPanel = false
            fs && fsEpPanel -> fsEpPanel = false
            fs && fsBar -> fsBar = false
            fs -> exitFs()
            else -> onBack()
        }
    }
    // TVBox 对标 F1：倍速参数跟随 player——重建（硬软解/引擎切换）时速率丢，effect 兜底重放
    // （T-C1 改走 ctl.setSpeed：系统档=setPlaybackParams+播放中自动暂停回起，见 SysCore.speedPlan）
    LaunchedEffect(player) { subOn = false } // P1-6：播放器重建（硬软解/引擎切换）字幕选择清零重选
    LaunchedEffect(player, speedX) {
        player.setSpeed(speedX)
    }
    LaunchedEffect(fs, fsSeq, fsBar, speedPanel) {
        // 面板打开暂停自隐（方案 §T-A3-3：选档不误收控件）
        if (fs && fsBar && !speedPanel) { delay(5000); fsBar = false } // web showFsBar 5s fade
    }
    LaunchedEffect(fs) { if (fs) runCatching { focusReq.requestFocus() } }
    // 两档返回走查（用户 2026-11-06）：控件 5s 自隐时焦点钮随 AnimatedVisibility 销毁、焦点落空，
    // DPAD 类键不再进 fsKeyMod（任意键唤出失效）→ 隐后把焦点重新锚回根
    LaunchedEffect(fs, fsBar) {
        if (fs && !fsBar) { runCatching { focusReq.requestFocus() }; fsRowFocusN.intValue = 0 }
        // 呼出控件后焦点落动作行首个钮（fadeIn 进场有延迟，有限重试；触屏模式 requestFocus 失败无副作用）
        // TVBox 对标 F1 修正：speedPanel 打开期间跳过重锚——胶囊 onClick 尾 pokeFs 键不变、
        // 协程不重启，原 720ms 重试窗会 120ms 一次抢回焦点，吃掉遥控移焦到胶囊的按键（装机走查实锤）
        if (fs && fsBar && !speedPanel) for (i in 0 until 6) {
            delay(120)
            // 让路判据（T-A3 走查二次实锤 2026-11-10）：钮行已有焦点（用户移焦或已锚中）=弃权，
            // 不再 120ms 抢回——原循环在模拟器慢渲染（fadeIn>窗）时吃掉用户 RIGHT 后落的抢锚
            if (fsRowFocusN.intValue > 0) break
            if (runCatching { fsActFr.requestFocus() }.getOrDefault(false)) break
        }
    }

    val g = lines().getOrNull(line)
    val total = g?.list?.size ?: 0
    val singleSlow = lines().size == 1 && speeds?.getOrNull(0).let { it != null && it >= 0 && it < PerfCore.SPEED_FAIR }
    val metaLine = listOfNotNull(
        (detail?.typeName ?: itemS.typeName), detail?.year?.ifBlank { null } ?: itemS.year,
        detail?.area?.ifBlank { null } ?: itemS.area,
    ).joinToString(" / ")
    val descFull = detail?.desc?.replace(Regex("<[^>]+>"), " ")?.replace("&nbsp;", " ")?.trim()?.takeIf { it.isNotEmpty() }
    var showDesc by remember { mutableStateOf(false) } // 用户 2026-11-03：简介全文弹窗

    // 遥控按键（TVBox 复刻，用户 2026-11-09）：控件可见=方向键全走焦点系统（←→在按钮行移焦，
    // 进度行不可聚焦＝需求 d）；控件隐藏=←→连发步进预览（首步±10s、≥500ms 加速、松手真 seek）、
    // ↑↓/其余键唤回控件；Enter/Center/Media=播放暂停；Back=两档返回不点亮控件
    val fsKeyMod = Modifier.onPreviewKeyEvent { ev ->
        if (!fs) return@onPreviewKeyEvent false
        // 锁定态：吞掉除退出外全部键（解锁靠左中常驻解锁钮点按，用户 2026-11-10 版式）
        if (locked && ev.key != Key.Escape && ev.key != Key.Back) return@onPreviewKeyEvent true
        val horiz = ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
        if (ev.type == KeyEventType.KeyUp) {
            // 滑动松手：一次性 seek 到预览位（tvSlideStop 语义）
            // 修订（用户 2026-11-10）：不再强制续播——保持原播放/暂停态，暂停中快进后退
            // 由右上常驻时间反映新时间点（fsPos 1s 轮询刷新）
            if (horiz && slide.active && !fsEpPanel) {
                slideFinal = slide.simPos; slideDirFinal = slide.dir
                player.seekTo(slide.simPos)
                slide.stop(); slideTick++
                return@onPreviewKeyEvent true
            }
            return@onPreviewKeyEvent false
        }
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // 两档返回（用户 2026-11-06）：Back 不呼出控件，否则返回瞬间 fsBar 被点亮、永远退不了全屏
        // 控件隐藏时的 ←→ 也不点亮控件（TVBox：滑动只出时间胶囊，底栏保持收起）
        val slideStep = horiz && !fsBar && !fsEpPanel && ev.key != Key.Back
        if (ev.key != Key.Back && !slideStep) pokeFs()
        // TVBox 对标 F1：倍速面板打开=单列上下走焦，OK/点按=选中并应用+收面板，Esc/Back=收
        if (speedPanel) {
            fun mvSp(d: Int) { speedFocus = (speedFocus + d).coerceIn(0, SPEED_OPTIONS.size - 1) }
            return@onPreviewKeyEvent when (ev.key) {
                Key.Escape, Key.Back -> { speedPanel = false; pokeFs(); true }
                Key.DirectionUp, Key.DirectionLeft -> { mvSp(-1); true }
                Key.DirectionDown, Key.DirectionRight -> { mvSp(1); true }
                Key.Enter, Key.DirectionCenter -> { speedX = SPEED_OPTIONS[speedFocus]; speedPanel = false; pokeFs(); true }
                else -> false
            }
        }
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
        when {
            // 控件可见：←→ 交回焦点系统在按钮行间移动（需求 d：按钮行内左右=切钮）
            slideStep -> {
                val ok = slide.step(
                    System.currentTimeMillis(),
                    if (ev.key == Key.DirectionRight) 1 else -1,
                    player.position, player.duration,
                )
                if (ok) slideTick++ else toast("快进/快退暂不可用（流未就绪）")
                true
            }
            horiz -> false
            else -> when (ev.key) {
                // 遥控 OK 双消费修复（用户 2026-11-10 走查实证）：控件亮时若预览吞 DOWN（toggle#1），
                // UP 仍会落到被焦钮上做 UP-only performClick（toggle#2）→ 播放钮净不暂停、别的钮还会误切播放态。
                // 控件亮（焦点在钮行）=不截和，整键交钮自身；控件隐（焦点锚根）=此处单次切播放。媒体键不受焦点布局影响恒切。
                Key.Enter, Key.MediaPlayPause, Key.DirectionCenter -> {
                    if (ev.key != Key.MediaPlayPause && fsBar && fsRowFocusN.intValue > 0) false
                    else { togglePlayFs(); true }
                }
                Key.Escape -> { exitFs(); true }
                else -> false
            }
        }
    }

    // A3（审计 2026-11-11）：遥控媒体键/频道键=播放暂停·±15s·上下集；锁屏态吞掉不响应；非媒体键放行
    DisposableEffect(player) {
        MediaKeys.handler = { code ->
            val plan = mediaKeyPlan(code, live = false)
            if (plan == null) false
            else if (locked) true
            else {
                when (plan) {
                    Mk.Toggle -> togglePlayFs()
                    Mk.SeekFwd -> doubleSeek(1)
                    Mk.SeekBack -> doubleSeek(-1)
                    // ⚠不能用组合期快照 total：effect 仅首帧跑一次，captured total=0（detail 未回）→
                    // 装机实锤 87 恒走「已是最后一集」；lines()/curEp 读 state 恒最新
                    Mk.NextItem -> { val tot = lines().getOrNull(line)?.list?.size ?: 0; val n = curEp + 1; if (n < tot) goEpTo(n) else toast("已是最后一集") }
                    Mk.PrevItem -> { val n = curEp - 1; if (n >= 0) goEpTo(n) else toast("已是第一集") }
                }
                true
            }
        }
        onDispose { MediaKeys.handler = null }
    }

    // TVBox 复刻（用户 2026-11-09）全屏左右半区竖调：右=STREAM_MUSIC 音量、左=window 亮度。
    // 与根 detectTapGestures(pokeFs) 并列共存：竖拖有位移→tap 被取消；纯 tap 无位移→竖拖不触发。
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager }
    fun curLevel(z: EdgeZone): Float = when (z) {
        EdgeZone.Volume -> {
            val a = audio ?: return 0.5f
            val mx = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (mx > 0) a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / mx else 0.5f
        }
        EdgeZone.Brightness -> {
            val b = (context as? android.app.Activity)?.window?.attributes?.screenBrightness ?: -1f
            if (b < 0f || b > 1f) 0.5f else b
        }
    }
    fun applyLevel(z: EdgeZone, l: Float) {
        when (z) {
            EdgeZone.Volume -> {
                val a = audio ?: return
                val mx = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (l * mx).toInt().coerceIn(0, mx), 0)
            }
            EdgeZone.Brightness -> {
                val act = context as? android.app.Activity ?: return
                val p = act.window.attributes
                p.screenBrightness = l.coerceIn(0.01f, 1f)
                act.window.attributes = p
            }
        }
    }
    fun hideAvLater() {
        avJob?.cancel()
        avJob = scope.launch { delay(500); avVisible = false }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
            .background(if (fs) Color.Black else MaterialTheme.colorScheme.background)
            .then(fsKeyMod)
            // 需求 d（用户 2026-11-09 走查）：根节点仅在「全屏+控件隐藏」时可聚焦——此刻无子可聚焦钮，
            // 方向键落根走 fsKeyMod 滑位（需求 c）；控件可见时根 focusable=false，焦点让给按钮行，
            // ←→ 在 FsAct 间移焦、进度 Canvas 非可聚焦永不被选中。可聚焦父节点不会向下遍历子钮，
            // 故必须让根「隐时聚焦、显时让位」而非全程聚焦（全程聚焦=焦点卡根、需求 d 失效的根因）
            .focusable(enabled = fs && !fsBar)
            .focusRequester(focusReq)
            // TVBox 复刻（用户 2026-11-09）单击=呼出控件、双击=三分区（左退15s/中切播放/右进15s）；
            // detectTapGestures 自带单/双共存：有 onDoubleTap 时单击延后 ~300ms 判定，无双击才派发 onTap
            .pointerInput(fs) {
                if (fs) detectTapGestures(
                    onTap = { if (!locked) pokeFs() },
                    onDoubleTap = { off ->
                        if (!locked) when (tapZone(off.x, size.width.toFloat())) {
                            TapZone.Left -> doubleSeek(-1)
                            TapZone.Center -> { togglePlayFs(); pokeFs() }
                            TapZone.Right -> doubleSeek(1)
                        }
                    },
                )
            }
            .pointerInput(fs, locked) {
                if (!fs || locked) return@pointerInput
                var z = EdgeZone.Volume
                var start = 0f
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { off -> z = edgeZone(off.x, size.width.toFloat()); start = curLevel(z); acc = 0f; avZone = z; avLevel = start; avVisible = true },
                    onVerticalDrag = { _, dY -> acc += dY; val nl = dragToLevel(acc, size.height.toFloat(), start); avLevel = nl; applyLevel(z, nl) },
                    onDragEnd = { hideAvLater() },
                    onDragCancel = { hideAvLater() },
                )
            },
    ) {
        if (!fs) {
            val wide = maxWidth > maxHeight
            // TVBox 精准复刻（用户 2026-11-09，activity_detail 实锤）：预览窗 vs_530 宽、高按 16:9
            // （蓝本 vs_530×vs_300=实测 994×563px，与 16:9 差 0.6% 忽略）；整页背景=海报模糊+压暗
            val winW = tvu(530)
            val epPageSize = 40
            var epPage by remember(line, itemS) { mutableStateOf(0) }
            var epRev by remember { mutableStateOf(false) } // tvSort 倒序：仅展示序反转，真下标不变
            LaunchedEffect(curEp, epPageSize, epRev, g) {
                val n = g?.list?.size ?: 0
                if (n > 0) { // 当前集换页跟读（连播翻页可见），倒序时按展示位换算
                    val pos = if (epRev) n - 1 - curEp else curEp
                    epPage = (pos / epPageSize).coerceAtLeast(0)
                }
            }
            // 默认焦点=播放窗（用户实拍：遥控器进详情默认选中播放窗显示白框）。
            // 装机实锤（2026-11-09 probe5）：本窗 Box 在 Compose 焦点系统里不可达——
            // FocusRequester 恒 false（含 tm=false+view 焦点已落 ACV 时）、方向搜索 UP 从线路
            // chip 跳过窗；clickable/focusable 两版皆同，唯一差异=内嵌 AndroidView(PlayerView)。
            // 改走旧包已实证路线（20261106l：OK 键焦点上窗）：焦点载体=PlayerView 本身（view 级），
            // isFocusableInTouchMode=true → requestFocus 触摸模式下也成功（模拟器/真机通吃）；
            // onFocusChange 驱动 winFocused 画白描边；方向键按下即 blur+FocusManager.moveFocus 交还
            // Compose 走焦，OK/Enter=进全屏。
            var winFocused by remember { mutableStateOf(false) }
            val pvRef = remember { mutableStateOf<android.view.View?>(null) } // 焦点载体窗视图（PlayerView 或系统档 SurfaceView）
            val winFocusMgr = LocalFocusManager.current
            // 窗上按方向键：先交还 view 焦点，再隔帧让 Compose 按方向走焦（同帧内 moveFocus
            // 会因焦点仍在本 View 而被忽略——焦点回调同帧抢焦同款坑）
            // 定向投递（用户 2026-11-09：窗 DOWN 应先落线路行，非集数——几何搜索在窗无
            // Compose 焦点锚点时落点不随布局，改显式指定目标）：目标 fr 抢不到再退回方向搜索
            val line1Fr = remember { FocusRequester() }
            val actRowFr = remember { FocusRequester() }
            val blurWin: (FocusRequester, FocusDirection) -> Boolean = { fr, fallback ->
                pvRef.value?.clearFocus()
                winFocused = false
                scope.launch {
                    withFrameNanos { }
                    if (!runCatching { fr.requestFocus() }.getOrDefault(false)) {
                        winFocusMgr.moveFocus(fallback)
                    }
                }
                true
            }
            LaunchedEffect(Unit) {
                repeat(20) {
                    if (winFocused) return@LaunchedEffect
                    pvRef.value?.requestFocus()
                    delay(150)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                (detail?.cover ?: itemS.pic)?.let { pv ->
                    AsyncImage(
                        model = pv, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(tvu(60)), // minSdk<31 blur 无效=直出，靠压暗层保可读
                    )
                }
                Box(Modifier.fillMaxSize().background(Color(0xB3000000))) // 压暗层（蓝本实拍模糊清晰可辨，70% 黑保文字对比度）
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = tvu(50), vertical = tvu(20)), verticalArrangement = Arrangement.spacedBy(tvu(10))) {
                    // topLayout：左预览窗 + 右信息列（activity_detail 双栏）。
                    // P1-5（审计 2026-11-11）：!wide 时双 fillMaxWidth 子项信息列分得 0 宽整列消失
                    // （现被 sensorLandscape 锁掩盖，解锁竖屏/分屏即触发）——窄宽改 Column 纵排
                    // RowColumnScope 非公开 API 不能做泛型接收者——改双 Modifier 参数：
                    // weight 在 Row/Column 各自 scope 就地调用后传入，内容块只消费 winMod/infoMod
                    @Composable
                    fun TopLayout(content: @Composable (winMod: Modifier, infoMod: Modifier) -> Unit) {
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(tvu(20))) {
                            content(Modifier.width(winW), Modifier.weight(1f).height(winW * 9f / 16f))
                        } else Column(verticalArrangement = Arrangement.spacedBy(tvu(10))) {
                            content(Modifier.fillMaxWidth(), Modifier.weight(1f, fill = false).fillMaxWidth())
                        }
                    }
                    TopLayout { winMod, infoMod ->
                        // TVBox 复刻（用户 2026-11-09 裁决①）：窗口模式关 Media3 内建控制器——
                        // 视频窗=可聚焦预览块，OK/点按=进全屏，←→=±15s（此前 OK 被内建控制器吞掉的根因）；
                        // 焦点态=透明底+2mm 白描边（button_detail_preview selector 实锤）
                        // 白描边画在外层 Box：PlayerView 的 SurfaceView 在窗口最上层打洞，描边加在
                        // AndroidView 层会被视频盖掉（装机走查实证）→ surface 内缩 2dp 露出边框
                        Box(
                            modifier = winMod
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                                // 焦点载体=PlayerView（view 级，见上 Ruling）；Box 只做触摸点击=进全屏，
                                // 不再挂 Compose 焦点节点（probe5 实锤本节点在焦点系统里不可达）
                                .pointerInput(Unit) { detectTapGestures(onTap = { winActivate() }) }
                                .then(if (winFocused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke) else Modifier),
                        ) {
                            // TVBox 对标 F2+C6：系统档=SurfaceView letterbox 窗，焦点载体同款 view 级配置；
                            // 缩放初始走 zoomPref（拉伸档系统壳无变形能力=近似 FIT 语义，由比例钮走 FIT↔ZOOM）
                            if (engineX == 1) {
                                SysPlayerView(
                                    ctl = player as SysCtl,
                                    fill = settings.zoomPref == 1,
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                    configure = { sv ->
                                        pvRef.value = sv
                                        sv.isFocusable = true
                                        sv.isFocusableInTouchMode = true
                                        sv.onFocusChangeListener = View.OnFocusChangeListener { _, has -> winFocused = has }
                                        sv.setOnKeyListener { _, keyCode, ev ->
                                            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                            when (keyCode) {
                                                android.view.KeyEvent.KEYCODE_ENTER,
                                                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> { winActivate(); true }
                                                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> blurWin(line1Fr, FocusDirection.Down)
                                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> blurWin(actRowFr, FocusDirection.Right)
                                                else -> false
                                            }
                                        }
                                    },
                                )
                            } else
                            // TVBox 对标 C10（2026-11-10）：渲染方式档变化时 key 触发整视图重建，进页即生效
                            key(settings.textureView) {
                            AndroidView(
                                factory = { ctx ->
                                    pvFor(ctx, settings.textureView).apply {
                                        pvRef.value = this
                                        this.player = player.exo // Exo 分支恒有实例（engineX==0）
                                        useController = false
                                        // TVBox 对标 C4：预览窗初始缩放档走全局设置（默认 0=FIT 与原行为一致）
                                        resizeMode = zoomResizeMode(settings.zoomPref)
                                        // view 级焦点窗（旧包 20261106l 实证路线）：可焦+触摸模式可焦，
                                        // 入场循环 requestFocus 必中；焦点态→winFocused 画白描边
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                        onFocusChangeListener = View.OnFocusChangeListener { _, has -> winFocused = has }
                                        setOnKeyListener { _, keyCode, ev ->
                                            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                            when (keyCode) {
                                                // OK/Enter=进全屏（与蓝本窗上按 OK 语义一致）
                                                android.view.KeyEvent.KEYCODE_ENTER,
                                                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> { winActivate(); true }
                                                // 方向键=交还 Compose 走焦（←→让位焦点系统，Ruling 2026-11-09：
                                                // ±15s 仅保留在全屏层）
                                                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> blurWin(line1Fr, FocusDirection.Down)
                                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> blurWin(actRowFr, FocusDirection.Right)
                                                else -> false
                                            }
                                        }
                                    }
                                },
                                // 硬解/软解/引擎重建：factory 只首建，player 换实例须靠 update 重绑
                                update = { if (it.player !== player.exo) it.player = player.exo },
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                            )
                            }
                            // TVBox 对标 C1：预览关=窗不黑屏空转，海报盖住未起播的 PlayerView（起播即撤）
                            if (noPreview) {
                                (detail?.cover ?: itemS.pic)?.takeIf { it.isNotBlank() }?.let { cp ->
                                    AsyncImage(
                                        model = cp, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                            }
                            if (winFocused) {
                                // 遥控提示（用户需求：显示白框+提示方向键选按钮操作）
                                Text(
                                    "方向键选择按钮 · OK 进入全屏",
                                    fontSize = tvSp(16),
                                    color = Color.White.copy(alpha = 0.92f),
                                    maxLines = 1,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = tvu(12))
                                        .background(TvColors.Overlay, RoundedCornerShape(50))
                                        .padding(horizontal = tvu(16), vertical = tvu(4)),
                                )
                            }
                            // 用户 2026-11-10 改：缓冲中/详情失败/播放失败等状态文案统一浮显在视频窗右上角
                            // （原详情信息列两处 Text 撤除；对齐全屏右上角「缓冲中…」的位置语义）
                            val stLine = listOfNotNull(errText, errorHint).joinToString("｜")
                            if (buffering || stLine.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(tvu(8)),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(tvu(4)),
                                ) {
                                    @Composable fun chip(txt: String, c: Color) = Text(
                                        txt, color = c, fontSize = tvSp(15), maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = tvu(360))
                                            .background(TvColors.Overlay, RoundedCornerShape(tvu(6)))
                                            .padding(horizontal = tvu(8), vertical = tvu(2)),
                                    )
                                    if (buffering) chip("缓冲中…", Color.Yellow)
                                    if (stLine.isNotEmpty()) chip(stLine, Color(0xFFFF6B6B))
                                }
                            }
                        }
                    // 右信息列（蓝本右列：标题 ts_30 bold→元信息 ts_18→演员/导演→简介 ts_16 maxLines4
                    // 行距1.1→Spacer 吃余量→动作行；列高=窗高，全文走「简介」弹窗）
                    Column(
                        // P1-5：TopLayout 窄宽=Column——winMod/infoMod 在各自 scope 调好 weight（宽=余宽限窗高/窄=余高不填满防压动作行）
                        modifier = infoMod,
                        verticalArrangement = Arrangement.spacedBy(tvu(4)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvu(8))) {
                            AdultTag(adultOf(itemS))
                            Text(detail?.title?.ifBlank { null } ?: itemS.name, fontSize = tvSp(30), fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        // 元信息行（tvSite|tvYear|tvArea|tvType ts_18 80%白；tvLang：CMS 详情解析无语言字段→略，见 ledger Ruling）
                        // 投影适配（2026-11-11）：源名+年代+地区+类型四全时总宽可超列宽→横滚兜底
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(tvu(10))) {
                            listOfNotNull(
                                itemS.sourceName,
                                (detail?.year?.ifBlank { null } ?: itemS.year)?.ifBlank { null },
                                (detail?.area?.ifBlank { null } ?: itemS.area)?.ifBlank { null },
                                (detail?.typeName?.ifBlank { null } ?: itemS.typeName)?.ifBlank { null },
                            ).filter { it.isNotBlank() }.forEach {
                                Text(it, fontSize = tvSp(18), color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        detail?.actor?.ifBlank { null }?.let { Text("主演：${it.take(100)}", fontSize = tvSp(16), color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        detail?.director?.ifBlank { null }?.let { Text("导演：$it", fontSize = tvSp(16), color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        descFull?.let { Text(it, fontSize = tvSp(16), color = Color(0xCCFFFFFF), maxLines = 4, lineHeight = tvSp(18), overflow = TextOverflow.Ellipsis) }
                        if (wide) Spacer(modifier = Modifier.weight(1f))
                        // 用户 2026-11-10 改：缓冲/错误等状态文案撤出详情信息列，统一浮显在视频窗右上角（见上方窗 Box）
                        // 动作行（Ruling④：播放(预览停摆才显)/快搜/倒序/测速/简介/收藏/🔗复制；
                        // 「返回」撤——遥控 Back 分层返回、触屏走系统手势）
                        Row(
                            // requiredHeight(tvu(40)=vs_40)：M3 Button 48dp 最小触控高会撑爆定高列
                            // （列高=窗高 213dp），蓝本钮高即 vs_40；行内全体等高符合规范
                            // 动作行↑=回播放窗（窗为 view 级焦点，Compose 方向搜索到不了，显式投递）
                            modifier = Modifier.horizontalScroll(rememberScrollState()).requiredHeight(tvu(40))
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                        pvRef.value?.requestFocus(); true
                                    } else false
                                },
                            horizontalArrangement = Arrangement.spacedBy(tvu(10)),
                        ) {
                            val af = Modifier.focusRequester(actRowFr)
                            // 投影适配（用户 2026-11-11）：内边距覆写为 tvu 等比（TmBtn 默认固定 dp
                            // 不动=他页零波及）——钮总宽与字号同比例律，任意 dpi 下占列宽比恒定
                            val actPad = PaddingValues(horizontal = tvu(12), vertical = tvu(4))
                            // C1：预览关时播放钮常驻；winActivate=待播则补起播链后进全屏（正常态等旧行为）
                            if (!playing || noPreview) TmBtn(modifier = af, contentPadding = actPad, onClick = { winActivate() }) { Text("播放", fontSize = tvSp(20)) }
                            TmBtn(modifier = if (playing) af else Modifier, contentPadding = actPad, onClick = { chDlg = true }) { Text("快搜", fontSize = tvSp(20)) }
                            TmBtn(contentPadding = actPad, onClick = { epRev = !epRev }) { Text(if (epRev) "正序" else "倒序", fontSize = tvSp(20)) }
                            TmBtn(contentPadding = actPad, onClick = {
                                LineSpeed.delete(LineSpeed.key(itemS.sourceKey, itemS.vodId, curEp))
                                userPickedLine = false
                                speeds = null
                                probeSpeeds(curEp)
                                toast("重新测速各线路…")
                            }) { Text("测速", fontSize = tvSp(20)) }
                            TmBtn(contentPadding = actPad, onClick = { showDesc = true }) { Text("简介", fontSize = tvSp(20)) }
                            TmBtn(contentPadding = actPad, onClick = { kept = keepSt.toggle(itemS) }) { Text(if (kept) "★ 已收藏" else "☆ 收藏", fontSize = tvSp(20)) }
                            // 复制直链钮已撤（用户 2026-11-09：不需要）
                        }
                    }
                }
                // 线路行＝mGridViewFlag 源行（chip 底 #803d3d3d 圆角 vs_10、选中=红下划线、
                // 焦点=40%红+白描边；分辨率/测速标签为 app 附加信息保留；单线判慢给换源出口）
                val ls = lines()
                if (ls.isNotEmpty()) {
                    Row(
                        // 线路行↑=回播放窗（同上，显式投递；↓ 自然走焦进集数网格）
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                    pvRef.value?.requestFocus(); true
                                } else false
                            },
                        horizontalArrangement = Arrangement.spacedBy(tvu(10)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ls.forEachIndexed { i, lg ->
                            val sel = i == line
                            val res = resOf(lg.flag)
                            val nm = (res?.let { lg.flag.replace(it, "", ignoreCase = true) } ?: lg.flag)
                                .trim().trim('-', '_', ' ').ifEmpty { lg.flag }
                            val tg = tagFor(i)
                            PillButton(
                                onClick = { if (!sel) switchLine(i) },
                                selected = sel,
                                modifier = if (i == 0) Modifier.focusRequester(line1Fr) else Modifier,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(tvu(6)),
                                ) {
                                    Text("$nm(${lg.list.size})", fontSize = tvSp(20), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (res != null) Text(res, fontSize = tvSp(14), color = Color(0xFF81C784))
                                    if (tg != null) {
                                        Text("·", fontSize = tvSp(14), color = Color(0x99FFFFFF))
                                        Text(tg.label, fontSize = tvSp(14), color = speedTagColor(tg.cls))
                                    }
                                }
                            }
                        }
                        if (singleSlow) {
                            PillButton(onClick = { chDlg = true }) { Text("⚡ 该源仅此1条线 · 换源", maxLines = 1, fontSize = tvSp(18)) }
                        }
                    }
                    // 选集区＝mSeriesGroupView 分组行（分页 tab，同款 flag chip）+ mGridView 6 列等宽网格
                    val eps = g?.list ?: emptyList()
                    val displayAll = epDisplayOrder(eps, epRev)
                    val showTabs = eps.size > epPageSize
                    val pageMax = ((eps.size - 1) / epPageSize).coerceAtLeast(0)
                    val curPage = if (showTabs) epPage.coerceIn(0, pageMax) else 0
                    val epStart = curPage * epPageSize
                    val epSub = displayAll.subList(epStart, minOf(epStart + epPageSize, displayAll.size))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tvu(6))) {
                        if (showTabs) {
                            val tabRange = { pg: Int -> "${pg * epPageSize + 1}-${minOf((pg + 1) * epPageSize, eps.size)}" }
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(tvu(10))) {
                                (0..pageMax).forEach { pg ->
                                    PillButton(onClick = { epPage = pg }, selected = pg == curPage) {
                                        Text(tabRange(pg), fontSize = tvSp(18))
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(tvu(10))) {
                            // 6 列等宽（蓝本实测 6 列 331px≈等分）；缺格补位保持列对齐；区内自滚、上方不动
                            epSub.chunked(6).forEach { rowCells ->
                                Row(horizontalArrangement = Arrangement.spacedBy(tvu(10))) {
                                    rowCells.forEach { (realIdx, e) ->
                                        EpCell(
                                            current = realIdx == curEp,
                                            label = e.name,
                                            modifier = Modifier.weight(1f),
                                            // 点选播该集并焦点回播放窗（实测 f 序列：网格 Enter 后回窗）
                                            onClick = { goEpTo(realIdx); scope.launch { withFrameNanos { }; pvRef.value?.requestFocus() } },
                                        )
                                    }
                                    repeat(6 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                }
            }
        } else {
            // ===== 全屏层＝web fsBar 信息 + TVBox 式面板（2026-11-03 定稿：进度行+动作行；不适用钮隐藏） =====
            // TVBox 对标 F2+C6：系统档 SurfaceView 窗——尺寸随 zoomMode 钮重算（fill=满屏裁切），黑底 letterbox
            if (engineX == 1) {
                SysPlayerView(ctl = player as SysCtl, fill = zoomMode, modifier = Modifier.fillMaxSize())
            } else
            // TVBox 对标 C10（2026-11-10）：渲染方式档变化时 key 触发整视图重建，进全屏即生效
            key(settings.textureView) {
            AndroidView(
                factory = { ctx -> pvFor(ctx, settings.textureView).apply {
                    this.player = player.exo; useController = false // Exo 分支恒有实例（engineX==0）
                    resizeMode = zoomResizeMode(settings.zoomPref) // TVBox 对标 C4：全屏窗初始缩放档走全局设置（默认 0=FIT 与原行为一致）
                    fsPv = this
                } },
                // 硬解/软解/引擎重建：player 换实例须靠 update 重绑，否则全屏黑屏
                update = { if (it.player !== player.exo) it.player = player.exo },
                modifier = Modifier.fillMaxSize(),
            )
            }
            // ===== TVBox 手机版样式复刻（用户 2026-11-04 参考图）：左上标题/分辨率·右上码率·右中当前大时间+剩余·红细线进度·底部图标动作行；跟手拖拽+遥控焦点均可用 =====
            val fsTitle = (detail?.title?.ifBlank { null } ?: itemS.name) + "：第${curEp + 1}集"
            val shownMs = if (fsDragPos >= 0) fsDragPos else fsPos
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).padding(20.dp, 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(width = 4.dp, height = 22.dp).background(TvColors.Red))
                        Text(fsTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    // TVBox 复刻（用户 2026-11-09）次行「竖条+清晰度」：短红竖条 + qualityLabel(hPx)，其后跟淡色原始分辨率
                    if (fsQualityTxt.isNotEmpty() || fsResTxt.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(width = 4.dp, height = 14.dp).background(TvColors.Red))
                        Text(fsQualityTxt.ifEmpty { fsResTxt }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        if (fsQualityTxt.isNotEmpty()) Text(fsResTxt, color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(20.dp, 14.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (fsBit.isNotEmpty()) Text(fsBit, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    if (buffering) Text("缓冲中…", color = Color.Yellow, style = MaterialTheme.typography.bodySmall)
                    // 用户 2026-11-10 修订：播放中什么都不显，仅暂停时显「时间+圆圈标+短进度线」标（FsPauseBadge）
                    if (!playing) FsPauseBadge(
                        fmtHms(shownMs / 1000) + " / " + (if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--:--"),
                        if (fsDur > 0) shownMs.toFloat() / fsDur else 0f,
                        { togglePlayFs(); pokeFs() },
                    )
                }
            }
            // 修订（用户 2026-11-10）：底栏控件自隐后，右上继续显示「⏸ + 暂停时间点」——
            // 快进/后退(←→/拖拽)后 fsPos 1s 轮询刷新，靠此时间反映当前位置；替代原居中进度胶囊
            AnimatedVisibility(visible = fsBar.not() && !playing && !locked && !buffering, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(20.dp, 14.dp)) {
                FsPauseBadge(
                    fmtHms(shownMs / 1000) + " / " + (if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--:--"),
                    if (fsDur > 0) shownMs.toFloat() / fsDur else 0f,
                    { togglePlayFs(); pokeFs() },
                )
            }
            // 用户 2026-11-10 实拍图版式：右中「<」=退出全屏回详情页（原 CenterEnd 大时间区迁至右下）
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(TvColors.Overlay)
                        .clickable { exitFs() }
                        .semantics { contentDescription = "退出全屏" }
                        .padding(10.dp),
                ) {
                    Icon(painterResource(R.drawable.tvb_v_back), null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            // 用户 2026-11-10 实拍图版式：左中=锁定钮（移出按钮行）；锁定后收起控件，左中改常驻「解锁」钮
            AnimatedVisibility(visible = fsBar && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(TvColors.Overlay)
                        .clickable { toggleLock() }
                        .semantics { contentDescription = "锁定屏幕" }
                        .padding(10.dp),
                ) {
                    Icon(painterResource(R.drawable.tvb_icon_lock), null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            if (fs && locked) {
                Box(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp)
                        .clip(CircleShape).background(TvColors.DetailBtn)
                        .clickable { toggleLock() }
                        .semantics { contentDescription = "解锁屏幕" }
                        .padding(10.dp),
                ) {
                    Icon(painterResource(R.drawable.tvb_icon_unlock), null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            // 用户 2026-11-10 实拍图版式：右下（控件上方）=当前时间大时钟 + 影片剩余/结束时刻
            // 修订（用户手机实拍：与右中「<」重叠）：字号 displaySmall→headlineMedium、bottom 贴合紧凑后的底栏
            // 再修（用户 2026-11-10）：靠右贴边（与右上对齐，end=20dp）——底栏紧凑+时钟缩小后与「<」已无竖向交叠；
            // 剩余/结束分隔改「/」
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 88.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fsClock.isNotEmpty()) Text(fsClock, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Normal)
                    if (fsDur > 0) Text(
                        "影片剩余 " + fmtHms((fsDur - shownMs).coerceAtLeast(0L) / 1000) + " | 影片结束 " + fsEndTxt,
                        color = Color(0xE6FFFFFF), fontSize = 15.sp,
                    )
                }
            }
            // A5（审计 2026-11-11）：片头窗内左下浮钮「跳过片头 MM:SS」——点按跳片头末（自动跳同判据，
            // 跳后/过窗即隐）；fmtSkip 死代码就此激活
            AnimatedVisibility(
                visible = fsBar && !locked && settings.skipIntroSec > 0 && skipInDone != curEp &&
                    fsPos < settings.skipIntroSec * 1000L,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 88.dp),
            ) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(TvColors.DetailBtn)
                        .clickable { player.seekTo(settings.skipIntroSec * 1000L); skipInDone = curEp; pokeFs() }
                        .semantics { contentDescription = "跳过片头" }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("跳过片头 ${fmtSkip(settings.skipIntroSec.toLong())}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedVisibility(visible = fsBar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                // 紧凑化修订（用户 2026-11-10 手机实拍）：撤底栏内小时钟行（与右下大时钟重复且占高），
                // padding/进度盒缩小，按钮行 40dp 触控高——整栏 ~74dp，为右下时钟与退出钮让出空间
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 进度行（参考图样式）：当前时间—红细线+圆点拇指—总时长；手指按住即拖（跟手预览、松手 seek）、单击定位；遥控 ←→ 仍走 ±15s（fsKeyMod）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmtHms(shownMs / 1000), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier.weight(1f).height(18.dp).pointerInput(fsDur) {
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
                                // TVBox 复刻（用户 2026-11-09）：轨道 40%白 → 次缓冲 深灰 → 已播 主题红 → 红圆拇指
                                val bf = if (fsDur > 0L) (fsBuf.toFloat() / fsDur).coerceIn(f, 1f) else 0f
                                drawLine(TvColors.Track, Offset(0f, half), Offset(size.width, half), 2.dp.toPx())
                                drawLine(TvColors.Buffer, Offset(0f, half), Offset(size.width * bf, half), 2.dp.toPx())
                                drawLine(TvColors.Red, Offset(0f, half), Offset(size.width * f, half), 4.dp.toPx())
                                drawCircle(TvColors.Red, 7.dp.toPx(), Offset(size.width * f, half))
                            }
                        }
                        Text(if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
                    }
                    // 动作行（参考图图标行映射本 app 能力；「▤ 选集」=全屏任意选集，遥控十字键可逐钮聚焦）
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 用户 2026-11-10 实拍图裁决：按钮行只留 7 钮。锁→左中、退出→右中、时钟→右下；跳过/循环/重置撤下。
                        // 2 次放权恢复：倍速（F1 11-10 裁决）、选集（P1-8 11-11 审计批准——fsEpPanel 面板曾无入口=死码，
                        // 全屏选集必须退全屏是功能缺口；对标 TVBox 底栏标配）
                        // OK 放行判据引用计数（用户 2026-11-10）：任一钮含焦即>0，钮间移焦 +1/−1 顺序无关
                        val rowFoc: (Boolean) -> Unit = { d -> fsRowFocusN.intValue = (fsRowFocusN.intValue + (if (d) 1 else -1)).coerceAtLeast(0) }
                        FsAct("上集", "上一集", off = curEp <= 0, res = R.drawable.tvb_v_prev, onFocused = rowFoc) { goEp(-1) }
                        FsAct("下集", "下一集", off = total > 0 && curEp >= total - 1, res = R.drawable.tvb_v_next, onFocused = rowFoc) { goEp(1) }
                        // P1-8 选集钮：接通右侧半透明集数网格（打开=自动对齐当前集，十字键选，Enter/点选即播，Back 关）
                        FsAct(if (fsEpPanel) "选集▣" else "选集", "打开/关闭选集面板", active = fsEpPanel, onFocused = rowFoc) {
                            // 与倍速面板互斥（按键分发 speedPanel 优先，同开会遮断选集走焦）
                            speedPanel = false
                            fsEpPanel = !fsEpPanel
                            if (fsEpPanel) pokeFs()
                        }
                        // 用户 2026-11-04：暂停态显示红色播放键，其余统一白色字形
                        // U+23F8 在 Android emoji 字体下无视 VS15 恒渲橙色，改用文字双竖条 ❚❚
                        FsAct(if (playing) "❚❚" else "▶", if (playing) "暂停" else "播放",
                            tint = if (playing) null else TvColors.Red,
                            res = if (playing) R.drawable.tvb_v_pause else R.drawable.tvb_v_play,
                            onFocused = rowFoc,
                            modifier = Modifier.focusRequester(fsActFr)) { togglePlayFs() }
                        // TVBox 对标 F1（2026-11-10 用户裁决：仅倍速一项放权恢复 UI）：文字胶囊，非 1.0 显 xN.N+红高亮
                        FsAct(if (speedX == 1f) "倍速" else "x" + speedLabel(speedX), "播放倍速",
                            active = speedX != 1f, onFocused = rowFoc) {
                            speedPanel = !speedPanel
                            if (speedPanel) fsEpPanel = false // P1-8 互斥（同上）
                            if (speedPanel) {
                                speedFocus = SPEED_OPTIONS.indexOfFirst { it == speedX }.coerceAtLeast(0)
                                pokeFs()
                            }
                        }
                        FsAct("比例", if (zoomMode) "画面满屏裁切" else "画面比例适配", active = zoomMode, res = R.drawable.tvb_v_aspect, onFocused = rowFoc) { toggleScale() }
                        FsAct("↺", "重播", res = R.drawable.tvb_v_replay, onFocused = rowFoc) { replayHere() }
                        // 用户 2026-11-10：硬解/软解钮（切换=重建播放器续位重播，见 toggleHwDecode）；T-C1 系统档无 codec 选择=置灰
                        FsAct(if (hwDecode) "硬" else "软", if (hwDecode) "当前硬解，点击切软解" else "当前软解，点击切硬解", off = engineX == 1, onFocused = rowFoc) { toggleHwDecode() }
                        // 用户 2026-11-10：音轨轮切钮（单轨流 toast 提示，见 toggleAudioTrack）；T-C1 系统档不支持音轨=置灰
                        FsAct("音轨", "切换音轨", res = R.drawable.tvb_v_audio, off = engineX == 1, onFocused = rowFoc) { toggleAudioTrack() }
                        // P1-6（审计 2026-11-11）：字幕轮切钮（含「关」档，toast 显轨标签）；系统档不支持=置灰（同音轨）
                        FsAct("字幕", "切换字幕轨", active = subOn, off = engineX == 1, onFocused = rowFoc) { toggleSubtitle() }
                        // TVBox 对标 F2+C6：播放器引擎胶囊钮（Exo=全功能/系统=轻量壳，切换续位重播，见 toggleEngine）
                        FsAct(if (engineX == 1) "系统" else "Exo", if (engineX == 1) "当前系统播放器，点击切回 Exo" else "当前 Exo 播放器，点击切系统播放器", onFocused = rowFoc) { toggleEngine() }
                    }
                }
            }
            // （用户 2026-11-10 版式裁决）倍速面板/右下解锁浮层撤下：锁/解锁改左中常驻，倍速无 UI 入口
            // TVBox 音量/亮度竖向滑条（竖拖时显示，左半区贴左、右半区贴右；松手 500ms 自隐）
            if (fs && avVisible) {
                TvSliderBar(
                    level = avLevel,
                    modifier = Modifier
                        .align(if (avZone == EdgeZone.Brightness) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 24.dp),
                )
            }
            // TVBox 复刻（用户 2026-11-09）暂停居中播放大钮：随控件条显隐，点按续播
            // 修（用户 2026-11-10）：Text("▶") 字形光学偏左不在圆心，改矢量图标右移 2dp 光学居中
            if (fs && !playing && fsBar && !locked && !buffering) {
                Box(
                    modifier = Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                        .background(TvColors.Overlay)
                        .clickable { player.playing = true; pokeFs() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.tvb_v_play), null, tint = TvColors.Red,
                        modifier = Modifier.size(30.dp).offset(x = 2.dp))
                }
            }
            // 暂停浮层居中胶囊已撤（用户 2026-11-10：不要再显示进度条在中间）；
            // 暂停时间点改由右上常驻「⏸+时间」承载（见 TopEnd 修订块），点画面中心仍可续播（TapZone.Center）
            // 快进/快退时间胶囊（TVBox tv_progress_container 复刻：方向图标+目标/总时间+3dp 细线；
            // 独立于底栏显隐，松手 1s 后自隐——需求 c：只见时间控件不见进度条）
            if (slideShow) {
                val showMs = if (slide.active) slide.simPos else slideFinal
                val d = if (slide.active) slide.dir else slideDirFinal
                // TVBox slide_info 复刻：渐变圆角胶囊底 + ts_26 白字
                Column(
                    modifier = Modifier.align(Alignment.Center)
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, TvColors.Overlay, Color.Transparent)),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (d < 0) "◀◀" else "▶▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            fmtHms(showMs / 1000) + " / " + (if (fsDur > 0) fmtHms(fsDur / 1000) else "--:--:--"),
                            color = Color.White, fontSize = 26.sp,
                        )
                    }
                    Box(Modifier.width(220.dp).height(3.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp))) {
                        val f = if (fsDur > 0) (showMs.toFloat() / fsDur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(TvColors.Red, RoundedCornerShape(2.dp)))
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
                                // TVBox 统一（用户 2026-11-09 遗留清理）：焦点=40%红底+白描边、当前集=红字（tvEpTextColor），撤旧黄框蓝底
                                modifier = if (i == fsFocusEp) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(8.dp)) else Modifier,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (i == fsFocusEp) TvColors.Red40 else TvColors.SeriesBg,
                                    contentColor = tvEpTextColor(i == curEp),
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
            // TVBox 对标 F1（2026-11-10）：倍速面板=居中方正 chips 列，走焦=40%红底+白描边、当前值=红字；
            // 打开期间控件暂停自隐（上方 LaunchedEffect speedPanel 条件）
            AnimatedVisibility(visible = speedPanel && fs, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Column(
                    modifier = Modifier.clip(RoundedCornerShape(TvColors.RadiusFs)).background(TvColors.Overlay)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SPEED_OPTIONS.forEachIndexed { i, x ->
                        TextButton(
                            onClick = { speedX = x; speedPanel = false; pokeFs() },
                            modifier = if (i == speedFocus) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, RoundedCornerShape(8.dp)) else Modifier,
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (i == speedFocus) TvColors.Red40 else Color.Transparent,
                                contentColor = if (x == speedX) TvColors.Red else Color.White,
                            ),
                        ) { Text(speedLabel(x), style = MaterialTheme.typography.labelMedium) }
                    }
                }
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
            openWith(sum, ResumeCtx(line, curEp, player.position / 1000))
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
