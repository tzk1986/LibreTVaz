package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 审计 2026-11-11 P1-4：弹窗尺寸按画布逻辑宽/高（widthPx/scale），不再吃原密度 LocalConfiguration dp——
 *  4K@160 投影档 cfg=3840dp 而画布=914dp，原式弹窗溢屏 3 倍 */
class TvScaleCanvasTest {

    @Test
    fun `canvas dp divides physical px by effective scale`() {
        // N1S 4K 投影：3840px @ scale=3840/914 → 逻辑宽=914
        val scale = tvCanvasScale(3840, 1.0f)
        assertEquals(914f, canvasDp(3840, scale), 0.5f)
    }

    @Test
    fun `phone档取density为scale 画布等于原配置dp行为不变`() {
        val density = 2.75f
        val scale = tvCanvasScale(2400, density) // 2400/914=2.626 < 2.75 → coerce 回 density
        assertEquals(density, scale, 0.001f)
        assertEquals(2400f / density, canvasDp(2400, scale), 0.01f)
        // 与 LocalConfiguration.screenWidthDp（=px/density）一致
        assertEquals(2400f / 2.75f, canvasDp(2400, scale), 0.01f)
    }

    @Test
    fun `dialog width ratio keeps seven-tenths of canvas not raw cfg`() {
        val scale = tvCanvasScale(3840, 1.0f)
        val w = canvasDp(3840, scale) * 0.7f
        assertEquals(639.8f, w, 1f)
        // 旧算法（cfg 3840*0.7=2688dp）在新密度下折 px 溢屏：证明必须修正
        org.junit.Assert.assertTrue(2688f * scale > 3840f)
    }
}
