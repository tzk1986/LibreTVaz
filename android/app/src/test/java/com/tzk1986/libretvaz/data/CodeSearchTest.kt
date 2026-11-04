package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 番号搜索引擎测试（用户 2026-11-04 需求：编号类查询规范化+标题提取匹配） */
class CodeSearchTest {

    @Test
    fun `同一番号各种写法归一`() {
        val expect = "hez|9"
        assertEquals(expect, CmsParser.codeCanon("hez-009"))
        assertEquals(expect, CmsParser.codeCanon("HEZ9"))
        assertEquals(expect, CmsParser.codeCanon("hez 009"))
        assertEquals(expect, CmsParser.codeCanon("hez009"))
        assertEquals(expect, CmsParser.codeCanon("HeZ_009"))
    }

    @Test
    fun `用户样例编号全部识别`() {
        mapOf(
            "ipzz-118" to "ipzz|118",
            "ntrd-001" to "ntrd|1",
            "EROFC-123" to "erofc|123",
            "FC2-4985073" to "fc2|4985073",
            "101226_100" to "101226|100",
            "HEYZO-3991" to "heyzo|3991",
            "T28-649" to "t28|649",
            "FC2PPV-1921054" to "fc2|1921054",
            "carib-0011" to "carib|11",
        ).forEach { (q, c) -> assertEquals(c, CmsParser.codeCanon(q)) }
    }

    @Test
    fun `fc2 与 fc2ppv 同族互中`() {
        assertTrue(CmsParser.codeMatch("FC2-PPV-4985073 无码", CmsParser.codeCanon("FC2-4985073")!!))
        assertTrue(CmsParser.codeMatch("FC2PPV-1921054 作品", CmsParser.codeCanon("fc2-ppv-1921054")!!))
        assertFalse(CmsParser.codeMatch("FC2-PPV-1921054 作品", CmsParser.codeCanon("FC2-4985073")!!))
    }

    @Test
    fun `标题带前后缀与尾字母变体均可提取`() {
        for (name in listOf("【HEZ-009】中文标题", "HEZ009 高清", "Hez-009V 修复版", "番号 HEZ-009 无码版")) {
            assertTrue(name, CmsParser.codeMatch(name, "hez|9"))
        }
        // 数字边界不误伤：0099 ≠ 009
        assertFalse(CmsParser.codeMatch("HEZ-0099 另一作", "hez|9"))
    }

    @Test
    fun `日期式编号双端一致`() {
        assertTrue(CmsParser.codeMatch("[101226_100] 自拍", CmsParser.codeCanon("101226_100")!!))
        assertTrue(CmsParser.codeMatch("101226-100 高清", CmsParser.codeCanon("101226100")!!))
        assertTrue(CmsParser.codeMatch("101226_20 早期", CmsParser.codeCanon("101226_020")!!))
    }

    @Test
    fun `非编号查询返回空并回落常规匹配`() {
        assertNull(CmsParser.codeCanon("东京热"))
        assertNull(CmsParser.codeCanon("2024"))
        assertNull(CmsParser.codeCanon("swag"))
        assertNull(CmsParser.codeCanon("hez-009 无码")) // 混排词整体不作编号查询
        assertNull(CmsParser.codeCanon("hd-1080"))      // 画质词在停用表
        assertTrue(CmsParser.relevantForSearch("东京热完整版", "东京热", false))
        assertFalse(CmsParser.relevantForSearch("其他片子", "东京热", false))
    }

    @Test
    fun `画质词不误提徽章`() {
        assertTrue(CmsParser.extractCodes("国产自拍 HD 1080 超清").isEmpty())
        assertEquals("MKG-101", CmsParser.codeRaw("MKG-101 主播实拍 1080P"))
    }
}
