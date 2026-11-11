package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计 2026-11-11 P1-2：ErrLog 入口脱敏——源 URL/域名=私有数据，日志（设置页可见+落盘 7 天+可截屏）不得回显 */
class ErrLogSanitizeTest {

    @Test
    fun `http url replaced by hashed source tag`() {
        val out = ErrLog.sanitize("GET https://some-vod-site.com/api.php/provide/vod failed")
        assertFalse(out.contains("some-vod-site.com"))
        assertFalse(out.contains("api.php"))
        assertTrue(out.contains("源["))
        assertTrue(out.endsWith(" failed"))
    }

    @Test
    fun `same host yields stable hash across messages`() {
        val a = ErrLog.sanitize("x https://AAA.example.net/y1 z")
        val b = ErrLog.sanitize("https://aaa.example.net/z9 超时")
        val ha = Regex("源\\[([0-9a-f]{8})]").find(a)!!.groupValues[1]
        val hb = Regex("源\\[([0-9a-f]{8})]").find(b)!!.groupValues[1]
        assertEquals("host 哈希须稳定可关联且大小写不敏感", ha, hb)
    }

    @Test
    fun `different hosts yield different hashes`() {
        val a = ErrLog.sanitize("https://one.site/path")
        val b = ErrLog.sanitize("https://two.site/path")
        val ha = Regex("源\\[([0-9a-f]{8})]").find(a)!!.groupValues[1]
        val hb = Regex("源\\[([0-9a-f]{8})]").find(b)!!.groupValues[1]
        assertFalse(ha == hb)
    }

    @Test
    fun `multiple urls all replaced and plain message untouched`() {
        val out = ErrLog.sanitize("a http://h1.dom/x b https://h2.dom:8080/y?k=v c")
        assertFalse(out.contains("h1.dom"))
        assertFalse(out.contains("h2.dom"))
        assertEquals(2, Regex("源\\[").findAll(out).count())
        assertEquals("普通文本原样", "普通文本原样", ErrLog.sanitize("普通文本原样"))
    }

    @Test
    fun `trailing punctuation not swallowed into host`() {
        // 句尾 URL 的终止符是空白；带端口/路径均整体替换
        val out = ErrLog.sanitize("失败：https://host.example.com/api.")
        assertFalse(out.contains("host.example.com"))
    }
}
