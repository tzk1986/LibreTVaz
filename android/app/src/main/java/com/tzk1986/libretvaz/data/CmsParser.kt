package com.tzk1986.libretvaz.data

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Apple CMS 响应解析（移植自 Web 端 src/lib/cms-parser.ts 核心逻辑）。
 */
object CmsParser {
    private val M3U8_IN_TEXT = Pattern.compile("\\${'$'}https?://[^\"'\\s]+?\\.m3u8")

    private val ADULT_KEYWORDS = listOf(
        "伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "日本福利片", "日本伦理片", "擦边短剧", "擦边",
        "韩国伦理", "里番动漫", "门事件", "萝莉少女", "制服诱惑", "国产传媒", "cosplay", "黑丝诱惑", "无码",
        "日本无码", "有码", "日本有码", "SWAG", "网红主播", "色情片", "同性片", "福利视频", "无码高清", "有码高清",
        "91视频", "自拍偷拍", "mini传媒",
    )

    private val PUNCT_SPACE = Pattern.compile("[\\s\\p{P}\\p{S}]+", Pattern.UNICODE_CHARACTER_CLASS)

    fun normalizeTitle(s: String): String =
        PUNCT_SPACE.matcher(s.lowercase()).replaceAll("")

    fun isAdultType(typeName: String?): Boolean =
        typeName != null && ADULT_KEYWORDS.any { typeName.contains(it) }

    fun isRelevant(name: String, wd: String): Boolean {
        val query = prepareQuery(wd) ?: return true
        return isRelevantPrepared(name, query)
    }

    private data class SeasonRef(val base: String, val season: Int, val explicit: Boolean)

    private class PreparedQuery(val normalized: String, val season: SeasonRef?, val tokens: List<String>)

    /**
     * 季数别名解析（与 Web 端一致）：查询侧允许「庆余年2」无空格写法；
     * 标题侧要求空格或明确「第N季」，避免把片名自带数字当成季数。
     */
    private fun parseSeasonSuffix(text: String, allowAnnotations: Boolean, bareNeedsSpace: Boolean): SeasonRef? {
        val t = text.trim()
        val explicitPattern = if (allowAnnotations)
            Regex("^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季.*$")
        else
            Regex("^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季$")
        val barePattern = if (bareNeedsSpace) Regex("^(.*?)\\s+([1-9]\\d?)$") else Regex("^(.*?)\\s*([1-9]\\d?)$")
        val explicit = explicitPattern.matchEntire(t)
        val match = explicit ?: barePattern.matchEntire(t) ?: return null
        val base = match.groupValues[1]
        if (normalizeTitle(base).isEmpty()) return null
        val value = match.groupValues[2]
        var season: Int? = value.toIntOrNull()
        if (season == null) {
            val chinese = value.replace("两", "二")
            val digits = "零一二三四五六七八九"
            season = when {
                chinese.length == 1 && chinese in "一二三四五六七八九" -> digits.indexOf(chinese)
                Regex("^[一二三四五六七八九]?十[一二三四五六七八九]?$").matches(chinese) -> {
                    val parts = chinese.split("十")
                    val tens = parts[0]
                    val units = if (parts.size > 1) parts[1] else ""
                    (if (tens.isNotEmpty()) digits.indexOf(tens) else 1) * 10 +
                        (if (units.isNotEmpty()) digits.indexOf(units) else 0)
                }
                else -> null
            }
        }
        season = season ?: return null
        return SeasonRef(base, season, explicit != null)
    }

    private fun prepareQuery(wd: String): PreparedQuery? {
        val normalized = normalizeTitle(wd)
        if (normalized.isEmpty()) return null
        return PreparedQuery(
            normalized = normalized,
            season = parseSeasonSuffix(wd, allowAnnotations = false, bareNeedsSpace = false),
            tokens = wd.trim().split(Regex("\\s+")).map { normalizeTitle(it) }.filter { it.isNotEmpty() },
        )
    }

    /** 数字必须完整命中，避免第 2 季误匹配 12 或 20（移植自 Web includesKeyword） */
    private fun includesKeyword(title: String, keyword: String): Boolean {
        if (title.isEmpty() || keyword.isEmpty()) return false
        val kwStartDigit = keyword.first() in '0'..'9'
        val kwEndDigit = keyword.last() in '0'..'9'
        var index = title.indexOf(keyword)
        while (index >= 0) {
            val before = if (index > 0) title[index - 1] else ' '
            val afterIdx = index + keyword.length
            val after = if (afterIdx < title.length) title[afterIdx] else ' '
            val beforeOk = !kwStartDigit || before !in '0'..'9'
            val afterOk = !kwEndDigit || after !in '0'..'9'
            if (beforeOk && afterOk) return true
            index = title.indexOf(keyword, index + 1)
        }
        return false
    }

    private fun matchesPrepared(nName: String, query: PreparedQuery): Boolean {
        if (includesKeyword(nName, query.normalized)) return true
        return query.tokens.size > 1 && query.tokens.all { includesKeyword(nName, it) }
    }

    private fun isRelevantPrepared(name: String, query: PreparedQuery): Boolean {
        val nName = normalizeTitle(name)
        if (nName.isEmpty()) return false
        val titleSeason = parseSeasonSuffix(name, allowAnnotations = true, bareNeedsSpace = true)
        if (query.season != null && titleSeason != null) {
            if (query.season.season != titleSeason.season) return false
            if (query.season.explicit || titleSeason.explicit) {
                val baseQuery = prepareQuery(query.season.base)
                if (baseQuery != null) return matchesPrepared(normalizeTitle(titleSeason.base), baseQuery)
            }
        }
        return matchesPrepared(nName, query)
    }

    fun parseSearchList(data: JSONObject, source: VodSource): List<VodSummary> {
        val arr = data.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val vod = arr.optJSONObject(i) ?: return@mapNotNull null
            VodSummary(
                sourceKey = source.url,
                sourceName = source.name,
                sourceUrl = source.url,
                vodId = vod.optString("vod_id"),
                name = vod.optString("vod_name"),
                pic = vod.optString("vod_pic").ifBlank { null },
                typeName = vod.optString("type_name").ifBlank { null },
                year = vod.optString("vod_year").ifBlank { null },
                area = vod.optString("vod_area").ifBlank { null },
                remarks = vod.optString("vod_remarks").ifBlank { null },
                isAdult = source.isAdult,
            )
        }
    }

    /** 源1$$$源2，集1$URL1#集2$URL2 → 优先含 .m3u8 直链的线路，均无则回退第一条 */
    fun extractEpisodesFromPlayUrl(playUrl: String): List<String> {
        if (playUrl.isBlank()) return emptyList()
        // 线路分隔符为三个美元符；"$$" 在 Kotlin 中是转义，需显式拼接
        val groups = playUrl.split("$".repeat(3))
            .map { group ->
                group.split("#").mapNotNull { ep ->
                    val parts = ep.split("$")
                    val url = if (parts.size > 1) parts[1].trim() else ""
                    url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
            }
            .filter { it.isNotEmpty() }
        if (groups.isEmpty()) return emptyList()
        return groups.firstOrNull { eps -> eps.any { it.contains(".m3u8") } } ?: groups[0]
    }

    fun extractM3u8FromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val m = M3U8_IN_TEXT.matcher(text)
        val out = mutableListOf<String>()
        while (m.find()) out.add(m.group().removePrefix("$"))
        return out
    }

    fun parseDetail(data: JSONObject, source: VodSource): VideoDetail? {
        val arr = data.optJSONArray("list")
        val vod = arr?.optJSONObject(0) ?: return null
        var episodes = extractEpisodesFromPlayUrl(vod.optString("vod_play_url"))
        if (episodes.isEmpty()) episodes = extractM3u8FromText(vod.optString("vod_content"))
        return VideoDetail(
            title = vod.optString("vod_name"),
            cover = vod.optString("vod_pic").ifBlank { null },
            desc = vod.optString("vod_content").ifBlank { null },
            typeName = vod.optString("type_name").ifBlank { null },
            year = vod.optString("vod_year").ifBlank { null },
            area = vod.optString("vod_area").ifBlank { null },
            actor = vod.optString("vod_actor").ifBlank { null },
            director = vod.optString("vod_director").ifBlank { null },
            sourceName = source.name,
            episodes = episodes,
        )
    }
}
