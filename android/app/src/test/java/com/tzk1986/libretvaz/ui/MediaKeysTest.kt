package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A3（审计 2026-11-11）媒体键→动作映射纯逻辑。InputEvent 真值（javap android.jar 实锤）：
 *  PLAY_PAUSE=85 PLAY=126 PAUSE=127 STOP=86 NEXT=87 PREV=88 REWIND=89 FFWD=90
 *  CH_UP=166 CH_DOWN=167 SKIP_FWD=272 SKIP_BACK=273 STEP_FWD=274 STEP_BACK=275（13+ TV 遥控）
 *  ⚠187=APP_SWITCH（装机误触实锤：曾把 187 当 NEXT 打出 recents） */
class MediaKeysTest {

    @Test
    fun `play pause trio toggles in both modes`() {
        for (k in listOf(85, 126, 127)) {
            assertEquals(Mk.Toggle, mediaKeyPlan(k, live = false))
            assertEquals(Mk.Toggle, mediaKeyPlan(k, live = true))
        }
    }

    @Test
    fun `ff rewind seek only for vod`() {
        assertEquals(Mk.SeekFwd, mediaKeyPlan(90, live = false))
        assertEquals(Mk.SeekFwd, mediaKeyPlan(272, live = false))
        assertEquals(Mk.SeekBack, mediaKeyPlan(89, live = false))
        assertEquals(Mk.SeekBack, mediaKeyPlan(273, live = false))
        assertNull(mediaKeyPlan(90, live = true))
        assertNull(mediaKeyPlan(89, live = true))
    }

    @Test
    fun `next prev media and channel map to item navigation`() {
        assertEquals(Mk.NextItem, mediaKeyPlan(87, live = false))
        assertEquals(Mk.NextItem, mediaKeyPlan(87, live = true))
        assertEquals(Mk.PrevItem, mediaKeyPlan(88, live = false))
        assertEquals(Mk.PrevItem, mediaKeyPlan(88, live = true))
        assertEquals(Mk.NextItem, mediaKeyPlan(166, live = true))
        assertEquals(Mk.PrevItem, mediaKeyPlan(167, live = true))
        assertEquals(Mk.NextItem, mediaKeyPlan(274, live = false))
        assertEquals(Mk.PrevItem, mediaKeyPlan(275, live = false))
    }

    @Test
    fun `stop app_switch and unknown keys unclaimed`() {
        assertNull(mediaKeyPlan(86, live = false))   // MEDIA_STOP 留系统
        assertNull(mediaKeyPlan(187, live = false))  // APP_SWITCH 绝不能吞
        assertNull(mediaKeyPlan(4, live = false))
    }
}
