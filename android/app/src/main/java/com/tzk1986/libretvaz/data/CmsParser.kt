package com.tzk1986.libretvaz.data

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Apple CMS 响应解析（移植自 Web 端 src/lib/cms-parser.ts 核心逻辑）。
 * 季数别名匹配暂未移植，M2c 补。
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

    /** 关键词归一后包含即相关；多词查询要求每词都命中（完整数字边界校验暂未移植） */
    fun isRelevant(name: String, wd: String): Boolean {
        val nName = normalizeTitle(name)
        if (nName.isEmpty()) return false
        val normalized = normalizeTitle(wd.trim())
        if (normalized.isEmpty()) return true
        if (nName.contains(normalized)) return true
        val tokens = wd.trim().split(Regex("\\s+")).map { normalizeTitle(it) }.filter { it.isNotEmpty() }
        return tokens.size > 1 && tokens.all { nName.contains(it) }
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
