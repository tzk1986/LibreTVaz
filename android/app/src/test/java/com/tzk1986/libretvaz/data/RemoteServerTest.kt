package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TVBox 同构自托管远程服务纯逻辑（用户 2026-11-10）：查询解析/局域网 IP 优选/收件队列/手机页渲染 */
class RemoteServerTest {

    @Test
    fun parseSendQuery_plain() {
        assertEquals("search" to "猫", parseSendQuery("m=search&t=%E7%8C%AB"))
    }

    @Test
    fun parseSendQuery_modeDefaultsToSearch() {
        assertEquals("search" to "dog", parseSendQuery("t=dog"))
    }

    @Test
    fun parseSendQuery_subMode() {
        assertEquals("sub" to "http://a.b/c.json", parseSendQuery("m=sub&t=http%3A%2F%2Fa.b%2Fc.json"))
    }

    @Test
    fun parseSendQuery_plusDecodesToSpace() {
        assertEquals("search" to("a b"), parseSendQuery("m=search&t=a+b"))
    }

    @Test
    fun parseSendQuery_rejectsEmptyText() {
        assertNull(parseSendQuery("m=search&t="))
        assertNull(parseSendQuery("m=search"))
    }

    @Test
    fun parseSendQuery_rejectsUnknownMode() {
        assertNull(parseSendQuery("m=evil&t=x"))
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
        val s = renderRemotePage("search")
        assert(s.contains("电视远程输入")) { "search 页应含标题" }
        assert(s.contains("/send")) { "手机页应 fetch /send" }
        val t = renderRemotePage("sub")
        assert(t.contains("电视订阅导入")) { "sub 页应含标题" }
    }
}
