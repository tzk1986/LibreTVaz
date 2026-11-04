package com.tzk1986.libretvaz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository(context: Context) {
    private val app = context.applicationContext

    /** 拉取并解析直播订阅；订阅可达数千行，超时放宽到 15s（与 Web 端一致）。asset:// 前缀读包内种子（B16 内置直播精筛表） */
    suspend fun load(source: LiveSource): LivePlaylist = withContext(Dispatchers.IO) {
        val isAsset = source.url.startsWith("asset://")
        val text = if (isAsset) {
            app.assets.open(source.url.removePrefix("asset://")).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            Http.get(source.url, timeoutMs = 15000)
        }
        val playlist = LivePlaylistParser.parse(text, if (isAsset) null else source.url)
        // iptv.hacks.tools / tv.iill.top 等对程序拉取返回 JS 跳转页而非播放列表
        if (playlist.channels.isEmpty() && text.trimStart().startsWith("<")) {
            throw IllegalStateException("该地址返回网页而非播放列表（可能需要浏览器访问或已失效）")
        }
        playlist
    }
}
