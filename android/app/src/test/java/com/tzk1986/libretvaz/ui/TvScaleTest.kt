package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// TVBox mm 虚拟屏换算纯函数单测。蓝本 dimens 全用 mm；420dpi 2400×1080 实测
// 1mm≈1.875px=0.714dp=短边(411dp)/576，故换算基准：dp = 短边dp × mm / 576。
class TvScaleTest {
    @Test
    fun `mm equals short side dp at full scale`() {
        assertEquals(411f, tvDp(576f, 411f), 0.001f)
    }

    @Test
    fun `linear proportion`() {
        assertEquals(35.677f, tvDp(50f, 411f), 0.01f)
        assertEquals(71.354f, tvDp(100f, 411f), 0.01f)
    }

    @Test
    fun `zero mm is zero dp`() {
        assertEquals(0f, tvDp(0f, 411f), 0f)
    }
}
