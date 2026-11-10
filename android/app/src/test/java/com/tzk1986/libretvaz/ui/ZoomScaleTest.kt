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

    @Test
    fun `zoomMode 初始仅满屏档为真`() {
        assertEquals(false, zoomModeInitial(0))
        assertEquals(true, zoomModeInitial(1))
        assertEquals(false, zoomModeInitial(2))
    }
}
