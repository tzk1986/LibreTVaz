package com.tzk1986.libretvaz.ui

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** TVBox 对标 C4（2026-11-10）：画面缩放全局档→media3 resizeMode 映射纯函数 */
class ZoomScaleTest {
    @Test
    fun `三档映射适配满屏拉伸`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, zoomResizeMode(0))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, zoomResizeMode(1))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, zoomResizeMode(2))
    }

    @Test
    fun `越界档回落适配`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, zoomResizeMode(-1))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, zoomResizeMode(7))
    }

    /** A10（审计 2026-11-11）：比例钮三档环 FIT→ZOOM→FILL（档值与 zoom_pref/ZOOM_LABELS 同域） */
    @Test
    fun `zoomNext 三档循环回绕`() {
        assertEquals(1, zoomNext(0))
        assertEquals(2, zoomNext(1))
        assertEquals(0, zoomNext(2))
    }
}
