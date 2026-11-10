package com.tzk1986.libretvaz.ui

import androidx.compose.ui.graphics.Color
import com.tzk1986.libretvaz.ui.theme.TvBtnKind
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.ui.theme.tvBtnContainer
import com.tzk1986.libretvaz.ui.theme.tvEpTextColor
import org.junit.Assert.assertEquals
import org.junit.Test

// TVBox 精准复刻（用户 2026-11-09，craft-anything selector 实锤）：
// 底色只由 焦点/按下 决定（state_focused→40%红+白描边；state_pressed→同40%红）；
// 「选中」不改底色——源/分组 chip 走红下划线，当前集走红字（蓝本 shape_source_flag_focus/
// shape_source_series_focus + EpisodeAdapter 红字）。常态底按 kind：
// Detail/Flag=#803D3D3D、Series=#733D3D3D、Fs=透明、PanelChip(button_dialog_main)=#99000000。
class TvColorTest {
    @Test
    fun `focused button gets 40pct red`() {
        assertEquals(TvColors.Red40, tvBtnContainer(focused = true, pressed = false, kind = TvBtnKind.Detail))
    }

    @Test
    fun `pressed button also gets 40pct red`() {
        assertEquals(TvColors.Red40, tvBtnContainer(focused = false, pressed = true, kind = TvBtnKind.Fs))
    }

    @Test
    fun `selected chip keeps resting grey - selection is underline not bg`() {
        assertEquals(Color(0x803D3D3D), tvBtnContainer(focused = false, pressed = false, kind = TvBtnKind.Flag))
    }

    @Test
    fun `idle detail button rests 50pct dark grey`() {
        assertEquals(Color(0x803D3D3D), tvBtnContainer(focused = false, pressed = false, kind = TvBtnKind.Detail))
    }

    @Test
    fun `idle episode cell rests 45pct dark grey`() {
        assertEquals(Color(0x733D3D3D), tvBtnContainer(focused = false, pressed = false, kind = TvBtnKind.Series))
    }

    @Test
    fun `idle fullscreen button is transparent`() {
        assertEquals(Color.Transparent, tvBtnContainer(focused = false, pressed = false, kind = TvBtnKind.Fs))
    }

    @Test
    fun `current episode text is theme red others white`() {
        assertEquals(TvColors.Red, tvEpTextColor(current = true))
        assertEquals(Color.White, tvEpTextColor(current = false))
    }
}
