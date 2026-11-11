package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TVBox 同构自托管远程服务纯逻辑（用户 2026-11-10）：查询解析/局域网 IP 优选/收件队列/手机页渲染 */
class RemoteServerTest {

    @Test
    fun parseSendQuery_plain() {
        assertEquals("search" to "猫", parseSendQuery("m=search&t=%E7%8C%AB&k=T", "T"))
    }

    @Test
    fun parseSendQuery_modeDefaultsToSearch() {
        assertEquals("search" to "dog", parseSendQuery("t=dog&k=T", "T"))
    }

    @Test
    fun parseSendQuery_subMode() {
        assertEquals("sub" to "http://a.b/c.json", parseSendQuery("m=sub&t=http%3A%2F%2Fa.b%2Fc.json&k=T", "T"))
    }

    @Test
    fun parseSendQuery_plusDecodesToSpace() {
        assertEquals("search" to("a b"), parseSendQuery("m=search&t=a+b&k=T", "T"))
    }

    @Test
    fun parseSendQuery_rejectsEmptyText() {
        assertNull(parseSendQuery("m=search&t=&k=T", "T"))
        assertNull(parseSendQuery("m=search&k=T", "T"))
    }

    @Test
    fun parseSendQuery_rejectsUnknownMode() {
        assertNull(parseSendQuery("m=evil&t=x&k=T", "T"))
    }

    // 审计 2026-11-11 P0-3：/send 必须带与本次会话 token 一致的 k，同网段设备盲打一律拒绝
    @Test
    fun parseSendQuery_rejectsWrongOrMissingToken() {
        assertNull(parseSendQuery("m=search&t=x", "T"))
        assertNull(parseSendQuery("m=search&t=x&k=WRONG", "T"))
        assertNull(parseSendQuery("m=search&t=x&k=", "T"))
    }

    @Test
    fun readRequestLine_returnsFirstLineTrimmedCr() {
        val input = "GET /remote?m=search HTTP/1.0\r\nHost: x\r\n".byteInputStream()
        assertEquals("GET /remote?m=search HTTP/1.0", readRequestLine(input, 8192))
    }

    @Test
    fun readRequestLine_capsOversizedAndEof() {
        val big = ByteArray(10000) { 'a'.code.toByte() } // 无换行的超长请求（半开/灌流 DoS 形态）
        assertNull(readRequestLine(big.inputStream(), 8192))
        assertNull(readRequestLine("GET /a".byteInputStream(), 8192)) // 未收到换行即断流
    }

    @Test
    fun pickLanIp_prefersWlanOverEthIgnoresLoopbackAndApipa() {
        val ifs = listOf(
            "lo" to "127.0.0.1",
            "ap0" to "192.168.43.1",
            "wlan0" to "192.168.1.66",
            "eth0" to "10.0.0.5",
            "dummy0" to "169.254.1.2",
        )
        assertEquals("192.168.1.66", pickLanIp(ifs))
    }

    @Test
    fun pickLanIp_fallsBackToEthThenOtherSiteLocal() {
        assertEquals("10.0.0.5", pickLanIp(listOf("lo" to "127.0.0.1", "eth0" to "10.0.0.5")))
        assertEquals("172.20.3.4", pickLanIp(listOf("rmnet0" to "172.20.3.4")))
    }

    @Test
    fun pickLanIp_skipsPublicAndApipe() {
        assertEquals("", pickLanIp(listOf("wlan0" to "8.8.8.8", "wlan1" to "169.254.9.9")))
    }

    @Test
    fun inbox_fifoThenOverflowKeepsNewest() {
        val q = RemoteInbox(cap = 2)
        q.put("search", "a"); q.put("search", "b"); q.put("search", "c")
        assertEquals("search" to "b", q.poll()) // 超容丢最旧 a
        assertEquals("search" to "c", q.poll())
        assertNull(q.poll())
    }

    @Test
    fun renderRemotePage_hasTitleAndSend() {
        val s = renderRemotePage("search", "TK1")
        assert(s.contains("电视远程输入")) { "search 页应含标题" }
        assert(s.contains("/send")) { "手机页应 fetch /send" }
        val t = renderRemotePage("sub", "TK2")
        assert(t.contains("电视订阅导入")) { "sub 页应含标题" }
    }

    // P0-3：内置 H5 页发送时须携带会话 token（随二维码下发，盲设备拿不到）
    @Test
    fun renderRemotePage_embedsToken() {
        assert(renderRemotePage("search", "TK1").contains("k=TK1")) { "手机页 fetch 应带 token" }
    }
}
