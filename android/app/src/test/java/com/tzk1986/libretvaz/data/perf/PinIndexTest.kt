package com.tzk1986.libretvaz.data.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ibm.icu.text.Collator
import com.ibm.icu.util.ULocale

/** web 拼音测试套对齐（B0）：修正 23 锚点、多音字、权重、淘汰、分桶性能 */
class PinIndexTest {

    // P0-1 后拼音走离线表：测试 JVM 直读随包 assets 安装（生产由 VocabPools.ensureLoaded 装）
    init { PinTable.install(java.io.File("src/main/assets", PinTable.ASSET_NAME).readBytes()) }

    @Test fun `表可用与锚点单调`() {
        val idx = PinIndex()
        assertTrue("需拼音表已装", idx.ok)
        val c = Collator.getInstance(ULocale.forLanguageTag("zh-Hans-CN")).apply { strength = Collator.TERTIARY }
        for (i in PinIndex.PIN_ANCHORS.indices) {
            val anchor = PinIndex.PIN_ANCHORS[i].second
            val ch = PinIndex.PIN_ANCHORS[i].first
            assertEquals("锚点「$anchor」首字母应为 $ch", ch.lowercaseChar(), idx.initials(anchor)[0])
            if (i > 0) {
                val prev = PinIndex.PIN_ANCHORS[i - 1].second
                assertTrue("锚点拼音须递增: $prev < $anchor", c.compare(anchor, prev) >= 0)
            }
        }
    }

    @Test fun `锚点修复回归 D与W`() {
        val idx = PinIndex()
        // D 锚=大（旧 bug 锚=多 致 da~dou 误入 C）
        assertEquals("d", idx.initials("大"))
        assertEquals("d", idx.initials("斗"))
        assertEquals("d", idx.initials("多"))
        // W 锚补齐（旧缺 W 致 wa~wu 并入 T）
        assertEquals("w", idx.initials("万"))
        assertEquals("w", idx.initials("蛙"))
        assertEquals("w", idx.initials("伪"))
        assertEquals("dldl", idx.initials("斗罗大陆"))
        assertEquals("wzz", idx.initials("伪装者"))
        assertEquals("bjws", idx.initials("北境往事"))
    }

    @Test fun `suggest 前缀联想`() {
        val idx = PinIndex()
        idx.addMany(listOf("庆余年", "白雪公主", "斗罗大陆", "伪装者", "北境往事"))
        assertTrue(idx.suggest("qyn").contains("庆余年"))
        assertTrue(idx.suggest("bx").contains("白雪公主"))
        assertEquals(listOf("斗罗大陆"), idx.suggest("dldl"))
        assertTrue(idx.suggest("wzz").contains("伪装者"))
        assertTrue(idx.suggest("bjws").contains("北境往事"))
        assertTrue(idx.suggest("").isEmpty())
        assertTrue(idx.suggest("12").isEmpty()) // 非纯字母→空（数字仅在词内占位）
    }

    @Test fun `多音字变体双读命中`() {
        val idx = PinIndex()
        idx.add("长发及腰")
        val vs = idx.variants("长发及腰")
        assertTrue("长应有 c/z 两读: $vs", vs.size >= 2 && vs.any { it.startsWith("c") } && vs.any { it.startsWith("z") })
        assertTrue(idx.suggest("cfjy").contains("长发及腰"))
        assertTrue(idx.suggest("zfjy").contains("长发及腰"))
    }

    @Test fun `权重排序 top 与 suggest`() {
        val idx = PinIndex(weights = mapOf("阿花" to 5L, "阿飞" to 1L))
        idx.addMany(listOf("阿花", "阿飞"))
        assertEquals(listOf("阿花", "阿飞"), idx.top(2))
        assertEquals(listOf("阿花", "阿飞"), idx.suggest("a"))
        idx.bump("阿飞", 10)
        assertEquals(listOf("阿飞", "阿花"), idx.top(2))
    }

    @Test fun `超限按权重淘汰 高频保留`() {
        val idx = PinIndex(max = 3, weights = mapOf("阿花" to 10L))
        idx.addMany(listOf("阿花", "阿飞", "阿猫", "阿狗"))
        assertEquals(3, idx.size())
        assertTrue(idx.list().contains("阿花"))
        assertFalse(idx.list().contains("阿飞")) // 低频最旧先淘汰
        assertTrue(idx.suggest("ah").contains("阿花"))
        assertTrue(idx.suggest("af").isEmpty()) // 淘汰后桶同步清理
    }

    @Test fun `fuzzy 包含召回与无关不混入`() {
        val idx = PinIndex()
        idx.addMany(listOf("风风草籽洲", "庆余年"))
        assertEquals(listOf("风风草籽洲"), idx.fuzzy("fcz")) // ffczz ⊃ fcz
        assertTrue(idx.fuzzy("qyn").contains("庆余年"))
        assertTrue(idx.fuzzy("zzz").isEmpty())
    }

    @Test fun `规模与桶查询性能`() {
        val idx = PinIndex()
        val t0 = System.currentTimeMillis()
        idx.addMany((1..5000).map { "测试${it}词" })
        val buildMs = System.currentTimeMillis() - t0
        assertEquals(5000, idx.size())
        assertTrue("5000 词构建 ${buildMs}ms 应 <3000ms", buildMs < 3000)
        val s0 = System.currentTimeMillis()
        val hits = idx.suggest("cs")
        val sugMs = System.currentTimeMillis() - s0
        assertTrue("分桶前缀查询 ${sugMs}ms 应 <50ms", sugMs < 50)
        assertTrue(hits.isNotEmpty())
    }
}
