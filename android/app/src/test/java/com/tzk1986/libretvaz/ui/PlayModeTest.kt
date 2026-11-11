package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A2（审计 2026-11-11）播放模式三档：0=顺序连播（受 autoNext 约束）/1=单集循环/2=列表循环（末集回绕首集） */
class PlayModeTest {

    @Test fun `mode cycles 0 to 1 to 2 to 0`() {
        assertEquals(1, playModeNext(0))
        assertEquals(2, playModeNext(1))
        assertEquals(0, playModeNext(2))
    }

    @Test fun `label per mode`() {
        assertEquals("连播", playModeLabel(0))
        assertEquals("单循环", playModeLabel(1))
        assertEquals("列表循环", playModeLabel(2))
    }

    @Test fun `single loop replays regardless of autoNext or position`() {
        assertEquals(EndAct.REPLAY, playModeOnEnd(1, 0, 24, true))
        assertEquals(EndAct.REPLAY, playModeOnEnd(1, 23, 24, false))
    }

    @Test fun `sequential follows autoNext and stops at last`() {
        assertEquals(EndAct.NEXT, playModeOnEnd(0, 3, 24, true))
        assertEquals(EndAct.STOP, playModeOnEnd(0, 3, 24, false))
        assertEquals(EndAct.STOP, playModeOnEnd(0, 23, 24, true))
    }

    @Test fun `list loop overrides autoNext and wraps at last`() {
        // 循环档=显式意图，autoNext 关也连播
        assertEquals(EndAct.NEXT, playModeOnEnd(2, 3, 24, false))
        assertEquals(EndAct.FIRST, playModeOnEnd(2, 23, 24, true))
        assertEquals(EndAct.FIRST, playModeOnEnd(2, 23, 24, false))
    }

    @Test fun `empty list never advances`() {
        // total=0（列表未回）时不可越权 Next/First——按 Stop
        assertEquals(EndAct.STOP, playModeOnEnd(0, 0, 0, true))
        assertEquals(EndAct.STOP, playModeOnEnd(2, 0, 0, true))
        // 单集循环不依赖列表长度
        assertEquals(EndAct.REPLAY, playModeOnEnd(1, 0, 0, true))
    }
}
