package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A4（审计 2026-11-11）音轨名纯格式化：name 优先→语言码映射→兜底编号 */
class AudioTrackLabelTest {

    @Test fun `name wins over language`() {
        assertEquals("国语", audioTrackLabel("chi", "国语", 0))
    }

    @Test fun `language codes map to chinese names`() {
        assertEquals("中文", audioTrackLabel("chi", null, 0))
        assertEquals("中文", audioTrackLabel("zho", "", 1))
        assertEquals("中文", audioTrackLabel("zh", null, 0))
        assertEquals("英文", audioTrackLabel("eng", null, 0))
        assertEquals("英文", audioTrackLabel("en", null, 0))
        assertEquals("粤语", audioTrackLabel("yue", null, 0))
        assertEquals("日语", audioTrackLabel("jpn", null, 0))
        assertEquals("韩语", audioTrackLabel("kor", null, 0))
        assertEquals("泰语", audioTrackLabel("tha", null, 0))
    }

    @Test fun `unknown or empty language falls back to index`() {
        assertEquals("音轨1", audioTrackLabel(null, null, 0))
        assertEquals("音轨2", audioTrackLabel("", null, 1))
        assertEquals("音轨3", audioTrackLabel("und", null, 2))
    }

    @Test fun `code matching is case insensitive`() {
        assertEquals("英文", audioTrackLabel("ENG", null, 0))
        assertEquals("西班牙语", audioTrackLabel("spa", null, 0))
        assertEquals("法语", audioTrackLabel("fre", null, 0))
    }
}
