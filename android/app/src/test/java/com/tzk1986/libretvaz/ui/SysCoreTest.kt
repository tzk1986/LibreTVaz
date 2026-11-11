package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TVBox 对标 T-C1（2026-11-10）：系统 MediaPlayer 引擎壳纯逻辑——表面尺寸/状态机/命令合法性/倍速降级 */
class SysCoreTest {

    // A10（审计 2026-11-11）：比例钮三档环，系统壳 surfaceSize 由 Boolean fill 改 Int mode
    // （0=适配/1=满屏裁切/2=拉伸充满，与 zoom_pref/ZOOM_LABELS 同域）
    @Test
    fun `surfaceSize fit centers without distortion`() {
        // 1920x1080 视频 @ 2400x1080 屏：scale=1 → 原尺寸居中（左右留黑边）
        assertEquals(1920 to 1080, SysCore.surfaceSize(2400, 1080, 1920, 1080, mode = 0))
        // 竖视频 1080x1920 @ 2400x1080：scale=0.5625 → 608x1080
        assertEquals(608 to 1080, SysCore.surfaceSize(2400, 1080, 1080, 1920, mode = 0))
    }

    @Test
    fun `surfaceSize fill covers screen by cropping`() {
        // 同 case crop：scale=2.2222 → 2400 宽撑满，高超屏由容器裁剪
        assertEquals(2400 to 4267, SysCore.surfaceSize(2400, 1080, 1080, 1920, mode = 1))
        // 横视频 crop 同 fit（屏即比例）
        assertEquals(2400 to 1350, SysCore.surfaceSize(2400, 1080, 16, 9, mode = 1))
    }

    @Test
    fun `surfaceSize stretch fills box ignoring aspect`() {
        // A10 拉伸充满：surface=容器尺寸，视频非等比拉满（原系统壳无此能力=近似 FIT，现补齐）
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, 1080, 1920, mode = 2))
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, 16, 9, mode = 2))
    }

    @Test
    fun `surfaceSize degenerates to screen when video size unknown`() {
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, 0, 0, mode = 0))
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, -1, 200, mode = 1))
        // 越界 mode 防御性回落适配
        assertEquals(608 to 1080, SysCore.surfaceSize(2400, 1080, 1080, 1920, mode = 7))
    }

    @Test
    fun `state machine maps MediaPlayer events to media3 semantics`() {
        assertEquals(SysCore.ST_READY, SysCore.nextState(SysCore.ST_IDLE, SysCore.Ev.PREPARED))
        assertEquals(SysCore.ST_BUF, SysCore.nextState(SysCore.ST_READY, SysCore.Ev.BUF_START))
        assertEquals(SysCore.ST_READY, SysCore.nextState(SysCore.ST_BUF, SysCore.Ev.BUF_END))
        // BUF_END 只在曾 BUF 时回 READY，其余保持（READY 态收到=保持 READY）
        assertEquals(SysCore.ST_READY, SysCore.nextState(SysCore.ST_READY, SysCore.Ev.BUF_END))
        assertEquals(SysCore.ST_END, SysCore.nextState(SysCore.ST_READY, SysCore.Ev.COMPLETED))
        assertEquals(SysCore.ST_IDLE, SysCore.nextState(SysCore.ST_END, SysCore.Ev.RESET))
        // PLAY/PAUSE 不改 playbackState（media3 语义 READY 含播与暂停，isPlaying 另走）
        assertEquals(SysCore.ST_READY, SysCore.nextState(SysCore.ST_READY, SysCore.Ev.PAUSE))
    }

    @Test
    fun `seek allowed only when prepared or ended`() {
        assertEquals(true, SysCore.canSeek(SysCore.ST_READY))
        assertEquals(true, SysCore.canSeek(SysCore.ST_END)) // 播完 seek 回 0 重播（单集循环链）
        assertEquals(false, SysCore.canSeek(SysCore.ST_IDLE))
        assertEquals(false, SysCore.canSeek(SysCore.ST_BUF))
    }

    @Test
    fun `speed plan pauses resume flag only when playing`() {
        // MediaPlayer.setPlaybackParams 播放中调用会自动暂停（API23+），需回起；暂停中直接设
        assertEquals(1.5f to true, SysCore.speedPlan(1.5f, playing = true))
        assertEquals(1.5f to false, SysCore.speedPlan(1.5f, playing = false))
    }

    // P1-7（审计 2026-11-11）：系统档音频焦点事件→动作裁决。wantPlay=播放意图（含缓冲中），
    // 第二值=新「焦点暂停」标记（true=待焦点恢复自动续播；永久 LOSS 清标记=不自动续）
    @Test
    fun `transient loss pauses only when we intended to play`() {
        assertEquals(SysCore.F_PAUSE to true, SysCore.focusPlan(SysCore.Fc.LOSS_TRANSIENT, wantPlay = true, pausedByFocus = false))
        assertEquals(SysCore.F_NOOP to false, SysCore.focusPlan(SysCore.Fc.LOSS_TRANSIENT, wantPlay = false, pausedByFocus = false))
        // 可闪避（导航播报）同样暂停——视频场景不做 duck 音量
        assertEquals(SysCore.F_PAUSE to true, SysCore.focusPlan(SysCore.Fc.LOSS_TRANSIENT_CAN_DUCK, wantPlay = true, pausedByFocus = false))
    }

    @Test
    fun `gain resumes only when paused by focus`() {
        assertEquals(SysCore.F_RESUME to false, SysCore.focusPlan(SysCore.Fc.GAIN, wantPlay = true, pausedByFocus = true))
        // 用户自己暂停的（未标记）：焦点回来不能擅自续播
        assertEquals(SysCore.F_NOOP to false, SysCore.focusPlan(SysCore.Fc.GAIN, wantPlay = true, pausedByFocus = false))
    }

    @Test
    fun `permanent loss abandons focus and kills auto resume`() {
        // 播放中被抢焦点（永久 LOSS）：暂停+释放焦点，不标记（来电级 GAIN 也不自动续，需手动）
        assertEquals(SysCore.F_PAUSE_ABANDON to false, SysCore.focusPlan(SysCore.Fc.LOSS, wantPlay = true, pausedByFocus = false))
        // 已因短暂 LOSS 暂停并标记：永久 LOSS 到达→不再重复暂停，清标记撤自动续播
        assertEquals(SysCore.F_NOOP to false, SysCore.focusPlan(SysCore.Fc.LOSS, wantPlay = true, pausedByFocus = true))
        // 本就没播：啥也不动
        assertEquals(SysCore.F_NOOP to false, SysCore.focusPlan(SysCore.Fc.LOSS, wantPlay = false, pausedByFocus = false))
    }

    @Test
    fun `repeated transient loss does not double pause`() {
        assertEquals(SysCore.F_NOOP to true, SysCore.focusPlan(SysCore.Fc.LOSS_TRANSIENT, wantPlay = true, pausedByFocus = true))
    }
}
