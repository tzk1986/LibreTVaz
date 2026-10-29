package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaylistParserTest {

    @Test
    fun `m3u standard extinf maps fields and dedups by url`() {
        val text = """
            #EXTM3U
            #PLAYLIST:我的直播
            #EXTINF:-1 tvg-id="cctv1" tvg-logo="https://logo.com/1.png" group-title="央视",CCTV-1
            https://live.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv1b" group-title="央视",CCTV-1 高清
            https://live.com/cctv1.m3u8
            #EXTINF:-1 group-title="卫视",湖南卫视
            rtp://239.2:5000
        """.trimIndent()
        val p = LivePlaylistParser.parse(text)
        assertEquals("我的直播", p.name)
        assertEquals(1, p.channels.size)
        val cctv = p.channels[0]
        assertEquals("CCTV-1", cctv.name)
        assertEquals("cctv1", cctv.tvgId)
        assertEquals("https://logo.com/1.png", cctv.logo)
        assertEquals("央视", cctv.group)
        assertEquals(listOf("央视"), p.groups)
    }

    @Test
    fun `m3u nonstandard attrs after title still extracts name`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,CCTV-2 tvg-id="cctv2" group-title="央视"
            https://live.com/cctv2.m3u8
        """.trimIndent()
        val p = LivePlaylistParser.parse(text)
        assertEquals("CCTV-2", p.channels[0].name)
        assertEquals("央视", p.channels[0].group)
    }

    @Test
    fun `extgrp supplies group when extinf lacks group-title`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,电影频道
            #EXTGRP:付费
            https://live.com/movie.m3u8
        """.trimIndent()
        val p = LivePlaylistParser.parse(text)
        assertEquals("付费", p.channels[0].group)
    }

    @Test
    fun `relative stream url resolved against baseUrl`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,A
            /streams/a.m3u8
            #EXTINF:-1,B
            b.ts
        """.trimIndent()
        val p = LivePlaylistParser.parse(text, "https://host.com/live/sub/list.m3u8?x=1")
        assertEquals("https://host.com/streams/a.m3u8", p.channels[0].url)
        assertEquals("https://host.com/live/sub/b.ts", p.channels[1].url)
    }

    @Test
    fun `channel without extinf falls back to url segment name`() {
        val p = LivePlaylistParser.parse("https://live.com/huavei.m3u8")
        assertEquals("huavei", p.channels[0].name)
    }

    @Test
    fun `txt genre format parsed`() {
        val text = """
            央视,#genre#
            CCTV-1,http://a.com/1.ts#http://b.com/1.m3u8
            CCTV-2,ftp://bad
            卫视,#genre#
            湖南卫视,https://c.com/hn.m3u8
        """.trimIndent()
        val p = LivePlaylistParser.parse(text)
        assertEquals(2, p.channels.size)
        assertEquals("http://a.com/1.ts", p.channels[0].url)
        assertEquals("央视", p.channels[0].group)
        assertEquals("湖南卫视", p.channels[1].name)
        assertEquals("卫视", p.channels[1].group)
        assertEquals(listOf("卫视", "央视"), p.groups) // 中文排序
        assertNull(p.name)
    }

    @Test
    fun `empty content yields empty playlist`() {
        val p = LivePlaylistParser.parse("")
        assertEquals(0, p.channels.size)
        assertEquals(0, p.groups.size)
    }
}
