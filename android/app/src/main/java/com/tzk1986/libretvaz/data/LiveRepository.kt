package com.tzk1986.libretvaz.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {
    /** 拉取并解析直播订阅；订阅可达数千行，超时放宽到 15s（与 Web 端一致） */
    suspend fun load(source: LiveSource): LivePlaylist = withContext(Dispatchers.IO) {
        val text = Http.get(source.url, timeoutMs = 15000)
        LivePlaylistParser.parse(text, source.url)
    }
}
