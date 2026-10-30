package com.tzk1986.libretvaz.data

/** 播放选线/换线纯函数，与 Web 端 debug-web/perf-core.js isStreamLink/pickNextLine 对齐 */
object Playback {
    private val STREAM_RE = Regex("\\.(m3u8|mp4|flv|mkv|ts|mpd)(\\?|$)", RegexOption.IGNORE_CASE)

    fun isStreamLink(link: String): Boolean = STREAM_RE.containsMatchIn(link)

    /** 默认线路：首个含流后缀地址的线路，找不到回退第一条非空（对应 web openDetail 选线） */
    fun pickDefaultLine(lines: List<PlayLine>): Int {
        val i = lines.indexOfFirst { g -> g.list.isNotEmpty() && g.list.any { isStreamLink(it.link) } }
        return if (i >= 0) i else lines.indexOfFirst { it.list.isNotEmpty() }
    }

    /** 自动换线候选：未试过≠当前，按同集(缺省第1集)地址是否流后缀优先排序 */
    data class LineCandidate(val index: Int, val episode: PlayEpisode)

    fun pickNextLine(lines: List<PlayLine>, line: Int, curEp: Int, tried: Set<Int>): List<LineCandidate> =
        lines.mapIndexed { i, g -> i to (g.list.getOrNull(curEp) ?: g.list.firstOrNull()) }
            .filter { (i, u) -> i != line && i !in tried && u != null }
            .map { (i, u) -> LineCandidate(i, u!!) }
            .sortedByDescending { isStreamLink(it.episode.link) }
}
