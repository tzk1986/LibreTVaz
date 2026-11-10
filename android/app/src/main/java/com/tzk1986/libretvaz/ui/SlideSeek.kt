package com.tzk1986.libretvaz.ui

/**
 * TVBox 长按连续快进复刻（用户 2026-11-09 指令；证据=classes18.dex VodController
 * tvSlideStart/tvSlideStop 反汇编还原，源码行 1180-1217）：
 * 遥控 ←→ 连发时只算预览位置并显示时间胶囊，滑动期间不真 seek，松手由调用方 seekTo(simPos)。
 * 步长规则原样：首步 ±10s；距上次 ≥500ms 每步 offset=offset×1.5+dir×15s（加速）；
 * <500ms 重复回到基础 ±10s。basePos 取按键时刻播放位（未 seek，滑动期间恒定，同 TVBox）。
 */
class SlideSeek {
    var active = false; private set
    var simPos = 0L; private set
    var dir = 0; private set
    private var offset = 0L
    private var lastAt = 0L

    /** 一次步进；durMs<=0（流未就绪）返回 false 不进入滑动 */
    fun step(nowMs: Long, dir: Int, basePosMs: Long, durMs: Long): Boolean {
        if (durMs <= 0L) return false
        this.dir = dir
        if (!active) {
            active = true
            offset = dir * BASE_MS
        } else if (nowMs - lastAt >= THRESHOLD_MS) {
            offset = (offset * ACCEL + dir * ACCEL_STEP_MS).toLong()
        } else {
            offset = dir * BASE_MS
        }
        lastAt = nowMs
        simPos = (basePosMs + offset).coerceIn(0L, durMs)
        return true
    }

    fun stop() {
        active = false
        dir = 0
        offset = 0
        simPos = 0
    }

    companion object {
        private const val BASE_MS = 10_000L
        private const val ACCEL_STEP_MS = 15_000L
        private const val THRESHOLD_MS = 500L
        private const val ACCEL = 1.5f
    }
}
