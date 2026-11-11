package com.tzk1986.libretvaz.data.perf

import com.ibm.icu.text.Collator
import com.ibm.icu.util.ULocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 审计 2026-11-11 P0-1（ICU 替换方案 A）永久回归：
 * 打包的 assets/pin_index.bin 必须与「ICU zh-Hans-CN Collator + 23 锚点区间法」逐字算出的表一致。
 * 表覆盖 0x3400..0x4DBF（Ext A）与 0x4E00..0x9FFF（基本区）共 27,584 字；0=无首字母（标点兜底由 PinTable 范围外返回）。
 */
class PinTableParityTest {

    private fun expectedTable(): ByteArray {
        val c = Collator.getInstance(ULocale.forLanguageTag("zh-Hans-CN")).apply { strength = Collator.TERTIARY }
        val anchors = PinIndex.PIN_ANCHORS
        return buildByteArray(27584) { ch ->
            var res = 0
            for ((init, anchor) in anchors) {
                if (c.compare(ch.toString(), anchor) >= 0) res = init.lowercaseChar().code else break
            }
            res
        }
    }

    private fun buildByteArray(size: Int, calc: (Char) -> Int): ByteArray {
        val b = ByteArray(size)
        var i = 0
        for (ch in '㐀'..'䶿') { b[i++] = calc(ch).toByte() }
        for (ch in '一'..'鿿') { b[i++] = calc(ch).toByte() }
        assertEquals("CJK 覆盖须 27584", size, i)
        return b
    }

    @Test
    fun `bundled bin matches ICU-computed table character by character`() {
        val file = File("src/main/assets", PinTable.ASSET_NAME)
        assertTrue("缺 ${file.path}（先用生成器产出）", file.exists())
        val bundled = file.readBytes()
        val expected = expectedTable()
        assertEquals("表长须 27584", 27584, bundled.size)
        for (i in 0 until expected.size) {
            val e = expected[i]
            val a = bundled[i]
            assertEquals("码位 U+%04X 首字母不一致".format(
                if (i < 6592) 0x3400 + i else 0x4E00 + (i - 6592)
            ), e, a)
        }
    }

    @Test
    fun `PinTable initialOf agrees with bin after install`() {
        val b = File("src/main/assets", PinTable.ASSET_NAME).readBytes()
        PinTable.install(b)
        assertTrue(PinTable.ok)
        assertEquals('d', PinTable.initialOf('大'))
        assertEquals('w', PinTable.initialOf('蛙'))
        assertEquals('a', PinTable.initialOf('阿'))
        assertEquals('z', PinTable.initialOf('中')) // U+4E2D 在基本区表内
        assertEquals(0.toChar(), PinTable.initialOf('，')) // 表范围外（非 CJK）返回 NUL
    }
}
