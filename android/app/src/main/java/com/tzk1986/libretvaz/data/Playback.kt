package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.PerfCore

/** 播放选线/换线纯函数：规则单一来源=perf-core（web）↔ PerfCore（本文件薄委托） */
object Playback {
    fun isStreamLink(link: String): Boolean = PerfCore.isStreamLink(link)

    /** 默认线路：首个含流后缀地址的线路，找不到回退第一条非空（对应 web openDetail 选线） */
    fun pickDefaultLine(lines: List<PlayLine>): Int {
        val i = lines.indexOfFirst { g -> g.list.isNotEmpty() && g.list.any { isStreamLink(it.link) } }
        return if (i >= 0) i else lines.indexOfFirst { it.list.isNotEmpty() }
    }

    /** B14（审计 2026-11-11）：换线保在播集——序号保留，新线集数不足钳到末集；空线归 0 */
    fun epMigrate(curEp: Int, newSize: Int): Int =
        if (newSize <= 0) 0 else curEp.coerceAtMost(newSize - 1)

    data class LineCandidate(val index: Int, val episode: PlayEpisode)

    /** 自动换线候选：B4 起带 speeds（实测慢/不可用线沉底，null=未知不惩罚） */
    fun pickNextLine(lines: List<PlayLine>, line: Int, curEp: Int, tried: Set<Int>, speeds: List<Long?>? = null): List<LineCandidate> =
        PerfCore.pickNextLine(lines, line, curEp, tried, speeds)
            .map { LineCandidate(it.index, it.episode) }
}
