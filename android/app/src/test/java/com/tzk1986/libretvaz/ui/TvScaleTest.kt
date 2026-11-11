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

    // 投影适配（用户 2026-11-11）：4K 低密度投影短边 dp 巨大→tvu 等比失控钮超大；
    // 封顶 420dp=手机横屏档，投影上复刻手机比例、动作行 6 钮全显
    @Test
    fun `phone sizes pass through unclamped`() {
        assertEquals(411f, tvMinSide(411f), 0.001f)
        assertEquals(360f, tvMinSide(360f), 0.001f)
    }

    @Test
    fun `tv and projector sizes clamp to 420`() {
        assertEquals(420f, tvMinSide(540f), 0.001f)
        assertEquals(420f, tvMinSide(720f), 0.001f)
        assertEquals(420f, tvMinSide(1440f), 0.001f)
        assertEquals(420f, tvMinSide(2160f), 0.001f)
    }
}
