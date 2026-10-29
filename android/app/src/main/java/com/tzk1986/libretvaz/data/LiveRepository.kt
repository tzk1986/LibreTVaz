package com.tzk1986.libretvaz.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {
    /** 拉取并解析直播订阅；订阅可达数千行，超时放宽到 15s（与 Web 端一致） */
    suspend fun load(source: LiveSource): LivePlaylist = withContext(Dispatchers.IO) {
        val text = Http.get(source.url, timeoutMs = 15000)
        val playlist = LivePlaylistParser.parse(text, source.url)
        // iptv.hacks.tools / tv.iill.top 等对程序拉取返回 JS 跳转页而非播放列表
        if (playlist.channels.isEmpty() && text.trimStart().startsWith("<")) {
            throw IllegalStateException("该地址返回网页而非播放列表（可能需要浏览器访问或已失效）")
        }
        playlist
    }
}
