package com.tzk1986.libretvaz.data

import java.text.Collator
import java.util.Locale

/**
 * 直播订阅解析：M3U（移植自 Web 端 m3u-parser.ts）+ TVBox txt 频道列表（Web 端不支持，本站扩展）。
 * 仅保留 http(s) 流地址；rtp/udp/rtsp 等 ExoPlayer 无法直接播放的协议丢弃。
 */
object LivePlaylistParser {
    private val ATTR_RE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val ATTR_STRIP_RE = Regex("""[A-Za-z0-9_-]+="[^"]*"""")
    private val EXTINF_PREFIX = Regex("""^#EXTINF\s*:\s*""", RegexOption.IGNORE_CASE)
    private val PLAYLIST_NAME = Regex("^#PLAYLIST:(.+)$", RegexOption.MULTILINE)
    private val STREAM_EXT = Regex("\\.(m3u8?|flv|ts|mp4)$", RegexOption.IGNORE_CASE)

    fun parse(content: String, baseUrl: String? = null): LivePlaylist {
        val isM3u = content.contains("#EXTM3U", ignoreCase = true) ||
            content.contains("#EXTINF", ignoreCase = true)
        val channels = if (isM3u) parseM3u(content, baseUrl) else parseTxt(content, baseUrl)
        val collator = Collator.getInstance(Locale.CHINA)
        val groups = channels.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .distinct().sortedWith { a, b -> collator.compare(a, b) }
        val name = if (isM3u) PLAYLIST_NAME.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } else null
        return LivePlaylist(name, channels, groups)
    }

    private fun parseM3u(content: String, baseUrl: String?): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        val seen = HashSet<String>()
        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var extGroup = ""
        var ctxActive = false

        for (rawLine in content.split(Regex("\r?\n"))) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase()

            if (upper.startsWith("#EXTINF")) {
                attrs = ATTR_RE.findAll(line).associate {
                    it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim()
                }
                val stripped = EXTINF_PREFIX.replace(ATTR_STRIP_RE.replace(line, ""), "")
                val commaIdx = stripped.indexOf(',')
                title = (if (commaIdx >= 0) stripped.substring(commaIdx + 1) else stripped)
                    .trim().removeSurrounding("\"")
                extGroup = ""
                ctxActive = true
                continue
            }
            if (upper.startsWith("#EXTGRP:")) {
                val g = line.substring("#EXTGRP:".length).trim()
                if (ctxActive && g.isNotEmpty() && !attrs.containsKey("group-title")) extGroup = g
                continue
            }
            if (line.startsWith("#")) continue

            val url = normalizeStreamUrl(line, baseUrl) ?: continue
            if (!seen.add(url)) { ctxActive = false; continue }
            val tvgId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() }
            val group = attrs["group-title"]?.takeIf { it.isNotEmpty() } ?: extGroup.takeIf { it.isNotEmpty() }
            out.add(
                LiveChannel(
                    id = tvgId ?: stableId(url),
                    name = if (ctxActive && title.isNotEmpty()) title else nameFromUrl(url),
                    url = url,
                    logo = normalizeStreamUrl(attrs["tvg-logo"] ?: "", baseUrl),
                    group = group,
                    tvgId = tvgId,
                )
            )
            ctxActive = false
        }
        return out
    }

    /** TVBox txt 格式：分组名,#genre# 换行后每行「频道名,URL1#URL2…」，多地址取第一个可用 http(s) */
    private fun parseTxt(content: String, baseUrl: String?): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        val seen = HashSet<String>()
        var group: String? = null
        for (rawLine in content.split(Regex("\r?\n"))) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(',')
            if (idx <= 0) {
                // 无逗号的裸 URL 行：按地址末段命名
                val bare = normalizeStreamUrl(line, baseUrl) ?: continue
                if (seen.add(bare)) out.add(LiveChannel(id = stableId(bare), name = nameFromUrl(bare), url = bare, group = group))
                continue
            }
            val name = line.substring(0, idx).trim()
            val rest = line.substring(idx + 1).trim()
            if (name.isEmpty()) continue
            if (rest.equals("#genre#", ignoreCase = true)) { group = name; continue }
            val url = rest.split('#').asSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.let { normalizeStreamUrl(it, baseUrl) } ?: continue
            if (!seen.add(url)) continue
            out.add(LiveChannel(id = stableId(url), name = name, url = url, group = group))
        }
        return out
    }

    /** 相对地址按订阅 URL 解析；仅放行 http(s)，其余协议（rtp/udp/rtsp 等）返回 null */
    internal fun normalizeStreamUrl(raw: String, baseUrl: String?): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        val base = baseUrl ?: return null
        val m = Regex("^https?://[^/]+").find(base) ?: return null
        val origin = m.value
        return when {
            s.startsWith("//") -> origin.substringBefore(":") + ":" + s
            s.startsWith("/") -> origin + s
            else -> {
                val path = base.substringAfter(origin).substringBefore('?').substringBefore('#')
                origin + path.substringBeforeLast('/', "/") + "/" + s
            }
        }
    }

    private fun nameFromUrl(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.split('/').lastOrNull { it.isNotEmpty() }
        if (last != null) {
            val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
                .let { STREAM_EXT.replace(it, "") }
            if (decoded.isNotEmpty()) return decoded
        }
        return url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "未命名频道" }
    }

    /** djb2 变体，与 Web 端 stableId 保持一致 */
    private fun stableId(input: String): String {
        var h = 5381L
        for (c in input) h = ((h shl 5) + h + c.code) and 0xFFFFFFFFL
        return java.lang.Long.toString(h, 36)
    }
}
