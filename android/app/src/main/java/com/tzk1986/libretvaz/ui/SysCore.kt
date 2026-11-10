package com.tzk1986.libretvaz.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TVBox 对标 T-C1（2026-11-10）：系统 MediaPlayer 引擎壳的纯逻辑部分——
 * 状态机（对齐 media3 STATE_ 语义）、seek 合法性、表面尺寸 letterbox/crop 计算、倍速降级方案。
 * 与 Android API 零耦合，供 SysCtl 包装层与 UI 直接调用，可完整单测。
 */
object SysCore {

    // 播放状态：数值对齐 media3 Player.STATE_*（IDLE=1 BUFFERING=2 READY=3 ENDED=4）
    const val ST_IDLE = 1
    const val ST_BUF = 2
    const val ST_READY = 3
    const val ST_END = 4

    /** MediaPlayer 事件归一 */
    enum class Ev { PREPARED, BUF_START, BUF_END, COMPLETED, RESET, PLAY, PAUSE }

    /** 状态迁移：PLAY/PAUSE 不改 playbackState（media3 语义 READY 含播与暂停，isPlaying 另走） */
    fun nextState(cur: Int, ev: Ev): Int = when (ev) {
        Ev.PREPARED -> ST_READY
        Ev.BUF_START -> ST_BUF
        Ev.BUF_END -> if (cur == ST_BUF) ST_READY else cur
        Ev.COMPLETED -> ST_END
        Ev.RESET -> ST_IDLE
        Ev.PLAY, Ev.PAUSE -> cur
    }

    /** 仅 READY/ENDED 可 seek（ENDED 态 seek 回 0 重播=单集循环链需要） */
    fun canSeek(state: Int): Boolean = state == ST_READY || state == ST_END

    /**
     * 渲染表面像素尺寸：fit=等比缩小取小边（letterbox 居中不拉伸）、fill=等比放大取大边（满屏裁切）。
     * 视频尺寸未知（vw/vh<=0）退化为屏幕尺寸。返回 (宽,高)。
     */
    fun surfaceSize(sw: Int, sh: Int, vw: Int, vh: Int, fill: Boolean): Pair<Int, Int> =
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) sw to sh
        else {
            val s = if (fill) max(sw.toFloat() / vw, sh.toFloat() / vh)
            else min(sw.toFloat() / vw, sh.toFloat() / vh)
            (vw * s).roundToInt() to (vh * s).roundToInt()
        }

    /**
     * 倍速方案：MediaPlayer.setPlaybackParams 在播放中调用会自动暂停（API23+），
     * 需上层设完后回起；暂停中直接设无副作用。返回 (倍率, 是否需要回起播放)。
     */
    fun speedPlan(rate: Float, playing: Boolean): Pair<Float, Boolean> = rate to playing
}
