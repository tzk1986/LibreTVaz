package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TVBox 对标 T-C1（2026-11-10）：系统 MediaPlayer 引擎壳纯逻辑——表面尺寸/状态机/命令合法性/倍速降级 */
class SysCoreTest {

    @Test
    fun `surfaceSize fit centers without distortion`() {
        // 1920x1080 视频 @ 2400x1080 屏：scale=1 → 原尺寸居中（左右留黑边）
        assertEquals(1920 to 1080, SysCore.surfaceSize(2400, 1080, 1920, 1080, fill = false))
        // 竖视频 1080x1920 @ 2400x1080：scale=0.5625 → 608x1080
        assertEquals(608 to 1080, SysCore.surfaceSize(2400, 1080, 1080, 1920, fill = false))
    }

    @Test
    fun `surfaceSize fill covers screen by cropping`() {
        // 同 case fill：scale=2.2222 → 2400 宽撑满，高超屏由容器裁剪
        assertEquals(2400 to 4267, SysCore.surfaceSize(2400, 1080, 1080, 1920, fill = true))
        // 横视频 fill 同 fit（屏即比例）
        assertEquals(2400 to 1350, SysCore.surfaceSize(2400, 1080, 16, 9, fill = true))
    }

    @Test
    fun `surfaceSize degenerates to screen when video size unknown`() {
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, 0, 0, fill = false))
        assertEquals(2400 to 1080, SysCore.surfaceSize(2400, 1080, -1, 200, fill = true))
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
}
