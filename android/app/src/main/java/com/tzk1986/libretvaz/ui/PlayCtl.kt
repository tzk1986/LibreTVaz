package com.tzk1986.libretvaz.ui

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tzk1986.libretvaz.data.Http
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TVBox 对标 F2+C6（2026-11-10）：点播播放器引擎抽象两档——ExoCtl 透传现行 ExoPlayer 全链（engine=0，
 * 行为与旧代码逐 API 等价）；SysCtl 包装 android.media.MediaPlayer 轻量壳（engine=1，系统解码/HLS 原生）。
 * 状态常量数值对齐 media3 Player.STATE_*（=SysCore ST_*），DetailScreen 两档同一套比较。
 */
interface PlayCtl {

    /** 仅 Exo 档非 null：音轨选择/videoFormat 等 Exo 独有能力经此访问，null=系统档降级不显 */
    val exo: ExoPlayer? get() = null

    /** playWhenReady 语义（可先设后 load，起播自动生效） */
    var playing: Boolean

    /** media3 STATE_*（1 IDLE/2 BUF/3 READY/4 END） */
    val state: Int
    val isPlaying: Boolean
    val duration: Long
    val position: Long

    /** 次缓冲位；系统档无此概念=退化为已播位（不伪造） */
    val buffered: Long
    val currentUri: String?
    val videoW: Int
    val videoH: Int

    fun load(u: String, startMs: Long)
    fun seekTo(ms: Long)
    fun setSpeed(rate: Float)

    /** 归一回调：与 Player.Listener 三方法一一对应；code=errorCodeName（Exo）/MEDIA_ERROR_SYSTEM(w/e)（系统） */
    interface Cb {
        fun onStateChanged(s: Int) {}
        fun onIsPlaying(p: Boolean) {}
        fun onError(code: String, detail: String) {}
    }

    fun addCb(cb: Cb)
    fun removeCb(cb: Cb)
    fun release()
}

/** Exo 档：现行 buildPlayer 的直接透传壳，API 面与旧 DetailScreen 用法逐一对应 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoCtl(private val p: ExoPlayer) : PlayCtl {

    override val exo get() = p
    override var playing: Boolean
        get() = p.playWhenReady
        set(v) { p.playWhenReady = v }
    override val state get() = p.playbackState
    override val isPlaying get() = p.isPlaying
    override val duration get() = p.duration
    override val position get() = p.currentPosition
    override val buffered get() = p.bufferedPosition
    override val currentUri get() = p.currentMediaItem?.localConfiguration?.uri?.toString()
    override val videoW get() = p.videoFormat?.width ?: 0
    override val videoH get() = p.videoFormat?.height ?: 0
    override fun load(u: String, startMs: Long) {
        p.setMediaItem(MediaItem.fromUri(u), startMs)
        p.prepare()
    }
    override fun seekTo(ms: Long) = p.seekTo(ms)
    override fun setSpeed(rate: Float) {
        p.playbackParameters = androidx.media3.common.PlaybackParameters(rate)
    }
    override fun release() = p.release()

    private val map = HashMap<PlayCtl.Cb, Player.Listener>()
    override fun addCb(cb: PlayCtl.Cb) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = cb.onStateChanged(state)
            override fun onIsPlayingChanged(isPlaying: Boolean) = cb.onIsPlaying(isPlaying)
            override fun onPlayerError(error: PlaybackException) = cb.onError(error.errorCodeName, error.message ?: "")
        }
        map[cb] = l
        p.addListener(l)
    }
    override fun removeCb(cb: PlayCtl.Cb) { map.remove(cb)?.let { p.removeListener(it) } }
}

/**
 * 系统 MediaPlayer 壳（T-C1）：reset→setDataSource(headers 带 Chrome UA 红线)→prepareAsync 链路，
 * 事件经 SysCore 状态机归一为 media3 语义。surface 由 SysPlayerView 的 holder 回调注入；
 * load 先于 surface 就绪时挂起，surfaceCreated 再真正开播（防预览关待播态空转）。
 * 降级面（方案 §10-R5）：无次缓冲/无实时码率/无音轨切换/无软硬解选择。
 */
class SysCtl(private val context: Context) : PlayCtl {

    private val mp = MediaPlayer().apply { setAudioStreamType(android.media.AudioManager.STREAM_MUSIC) }
    private val cbs = CopyOnWriteArrayList<PlayCtl.Cb>()
    private var st = SysCore.ST_IDLE
    private var surface: Surface? = null
    private var uri: String? = null
    private var startMs = 0L
    private var prepared = false
    private var rate = 1f
    private var released = false

    // P1-7（审计 2026-11-11）：系统档手挂音频焦点——MediaPlayer 无 Exo handleAudioFocus 的自动托管，
    // 不挂=来电/他人出声重叠。裁决纯逻辑在 SysCore.focusPlan（单测覆盖），此处只做 API 桥接：
    // 起播申请、暂停/播完/释放放弃；短暂 LOSS 暂停保焦待 GAIN 续播，永久 LOSS 弃焦不自动续
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusReq: android.media.AudioFocusRequest? = null // API26+ 句柄，弃焦须同对象
    private var pausedByFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { ev ->
        val fc = when (ev) {
            AudioManager.AUDIOFOCUS_GAIN -> SysCore.Fc.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> SysCore.Fc.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> SysCore.Fc.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> SysCore.Fc.LOSS_TRANSIENT_CAN_DUCK
            else -> return@OnAudioFocusChangeListener
        }
        val (act, mark) = SysCore.focusPlan(fc, wantPlay = playing, pausedByFocus)
        pausedByFocus = mark
        when (act) {
            SysCore.F_PAUSE -> runCatching { mp.pause() }.also { fireIsPlaying() }
            SysCore.F_PAUSE_ABANDON -> {
                abandonFocus()
                runCatching { mp.pause() }.also { fireIsPlaying() }
            }
            SysCore.F_RESUME -> runCatching { mp.start() }.also { fireIsPlaying() }
        }
    }

    private fun requestFocus() {
        val a = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusReq ?: android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusReq = req
            runCatching { a.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { a.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
        }
    }

    private fun abandonFocus() {
        val a = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) focusReq?.let { runCatching { a.abandonAudioFocusRequest(it) } }
        else @Suppress("DEPRECATION") runCatching { a.abandonAudioFocus(focusListener) }
    }

    /** Compose 可观测视频尺寸（SysPlayerView 据此重算 letterbox/crop） */
    private val _vsize = mutableStateOf(0 to 0)
    val vsize: State<Pair<Int, Int>> get() = _vsize

    init {
        mp.setOnPreparedListener {
            prepared = true
            if (startMs > 0) runCatching { mp.seekTo(startMs.toInt()) }
            if (rate != 1f) applySpeed()
            setState(SysCore.ST_READY)
            // 缓冲期已因焦点暂停（pausedByFocus）：prepared 不抢起，等 GAIN 或手动
            if (playing && !pausedByFocus) { requestFocus(); runCatching { mp.start() } }
            fireIsPlaying()
        }
        mp.setOnCompletionListener { abandonFocus(); setState(SysCore.ST_END) }
        mp.setOnErrorListener { _, what, extra ->
            prepared = false
            fireError("MEDIA_ERROR_SYSTEM($what/$extra)", "")
            true
        }
        mp.setOnInfoListener { _, t, _ ->
            when (t) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> setState(SysCore.nextState(st, SysCore.Ev.BUF_START))
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> setState(SysCore.nextState(st, SysCore.Ev.BUF_END))
            }
            true
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> _vsize.value = w to h }
    }

    /** SysPlayerView holder 回调注入：null=窗销毁不摘除（进全屏即有新窗接续，防中途黑屏） */
    fun setSurface(s: Surface?) {
        if (s == null) return
        surface = s
        if (!prepared) startPending() else runCatching { mp.setSurface(s) }
    }

    override val exo: ExoPlayer? get() = null

    override var playing: Boolean = true
        set(v) {
            field = v
            if (!prepared) return
            pausedByFocus = false // 手动操作撤销焦点自动续播标记（GAIN 不再擅自续）
            if (v) { requestFocus(); runCatching { mp.start() } }
            else { runCatching { mp.pause() }; abandonFocus() }
            fireIsPlaying()
        }

    override val state: Int get() = st
    override val isPlaying: Boolean get() = prepared && runCatching { mp.isPlaying }.getOrDefault(false)
    override val duration: Long get() = if (prepared) runCatching { mp.duration.toLong() }.getOrDefault(0L) else 0L
    override val position: Long get() = if (prepared) runCatching { mp.currentPosition.toLong() }.getOrDefault(0L) else 0L
    override val buffered: Long get() = position
    override val currentUri: String? get() = uri
    override val videoW: Int get() = vsize.value.first
    override val videoH: Int get() = vsize.value.second

    override fun load(u: String, startMs: Long) {
        uri = u
        this.startMs = startMs
        prepared = false
        _vsize.value = 0 to 0
        setState(SysCore.ST_BUF) // 与 Exo prepare→BUFFERING 对齐，UI 立即有缓冲反馈
        startPending()
    }

    private fun startPending() {
        val u = uri ?: return
        val s = surface ?: return
        runCatching {
            mp.reset()
            prepared = false
            mp.setSurface(s)
            mp.setDataSource(context, Uri.parse(u), mapOf("User-Agent" to Http.CHROME_UA))
            mp.prepareAsync()
        }.onFailure { fireError("MEDIA_ERROR_IO", it.message ?: it.javaClass.simpleName) }
    }

    override fun seekTo(ms: Long) {
        if (SysCore.canSeek(state)) runCatching { mp.seekTo(ms.toInt()) }
    }

    override fun setSpeed(rate: Float) {
        this.rate = rate
        if (prepared) applySpeed()
    }

    /** API23+ 才有 playbackParams；播放中调用自动暂停（SysCore.speedPlan），设完回起 */
    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < 23) return
        val (r, resume) = SysCore.speedPlan(rate, runCatching { mp.isPlaying }.getOrDefault(false))
        runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(r)
            if (resume) mp.start()
            fireIsPlaying()
        }
    }

    override fun addCb(cb: PlayCtl.Cb) { cbs.addIfAbsent(cb) }
    override fun removeCb(cb: PlayCtl.Cb) { cbs.remove(cb) }

    override fun release() {
        if (released) return
        released = true
        abandonFocus() // P1-7：释放即弃焦（Exo 档由 player.release 内部自动弃，系统档手挂须自己收）
        runCatching { mp.reset(); mp.release() }
    }

    private fun setState(s: Int) {
        if (st == s) return
        st = s
        cbs.forEach { it.onStateChanged(s) }
    }

    private fun fireIsPlaying() { val p = isPlaying; cbs.forEach { it.onIsPlaying(p) } }

    private fun fireError(code: String, detail: String) {
        setState(SysCore.ST_IDLE)
        // B21（审计 2026-11-11）：错误即停播须随发 onIsPlaying(false)，对齐 Exo 档错误后
        // media3 自动派发 onIsPlayingChanged(false) 的语义；否则播放钮停在「播放中」态。
        // 桥接代码无纯逻辑可拆（SysCtl 依赖真机 MediaPlayer），以装机抽验覆盖。
        cbs.forEach { it.onIsPlaying(false) }
        cbs.forEach { it.onError(code, detail) }
    }
}

/**
 * 系统档渲染窗（T-C1）：SurfaceView + holder 注入 surface；尺寸=SysCore.surfaceSize 对容器取整
 * （fit 等比居中留黑边 / fill 撑满裁切），父层黑底即 letterbox。configure=焦点载体等 view 级配置钩子
 * （预览窗复刻 PlayerView 分支同款：可焦+按键转交）。
 */
@Composable
internal fun SysPlayerView(
    ctl: SysCtl,
    fill: Boolean,
    modifier: Modifier = Modifier,
    configure: (SurfaceView) -> Unit = {},
) {
    val density = LocalDensity.current
    var boxW by remember { mutableIntStateOf(0) }
    var boxH by remember { mutableIntStateOf(0) }
    val (vw, vh) = ctl.vsize.value
    Box(modifier.onSizeChanged { boxW = it.width; boxH = it.height }, contentAlignment = Alignment.Center) {
        val (w, h) = if (boxW > 0) SysCore.surfaceSize(boxW, boxH, vw, vh, fill) else 0 to 0
        if (w > 0 && h > 0) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = ctl.setSurface(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = ctl.setSurface(h.surface)
                            override fun surfaceDestroyed(h: SurfaceHolder) = ctl.setSurface(null)
                        })
                        configure(this)
                    }
                },
                modifier = Modifier.size(
                    with(density) { w.toDp() },
                    with(density) { h.toDp() },
                ),
            )
        }
    }
}
