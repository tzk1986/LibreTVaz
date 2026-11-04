package com.tzk1986.libretvaz.data

import org.json.JSONArray
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

    // Android 的 java.util.regex 不支持 UNICODE_CHARACTER_CLASS（静态初始化会抛 IllegalArgumentException），
    // 改逐字符 Unicode 类别过滤，JVM 与 Android 行为一致，等价 web 端 /[\s\p{P}\p{S}]+/gu
    private val PUNCT_SYMBOL_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    ).map { it.toInt() }.toSet()

    private fun isPunctOrSymbol(c: Char): Boolean =
        Character.getType(c.code).toInt() in PUNCT_SYMBOL_TYPES

    fun normalizeTitle(s: String): String =
        s.lowercase().filterNot { it.isWhitespace() || isPunctOrSymbol(it) }

    fun isAdultType(typeName: String?): Boolean =
        typeName != null && ADULT_KEYWORDS.any { typeName.contains(it) }

    fun isRelevant(name: String, wd: String, fuzzy: Boolean = true): Boolean {
        val query = prepareQuery(wd) ?: return true
        val rel = isRelevantPrepared(name, query)
        if (rel || !fuzzy) return rel
        // 实验室「模糊搜索」：核心片名互含也算命中（源片名常带【高清】/导演名等尾缀），web fongmi_fuzzy 等价
        val cq = com.tzk1986.libretvaz.data.perf.PerfCore.coreName(query.normalized)
        val cn = com.tzk1986.libretvaz.data.perf.PerfCore.coreName(name)
        return cq.isNotEmpty() && cn.isNotEmpty() && (cn.contains(cq) || cq.contains(cn))
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

    // ===== 番号引擎（用户 2026-11-04 需求，web 蓝本之外的资源口搜索增强）=====
    // hez-009 / ipzz-118 / FC2-4985073 / FC2PPV-1921054 / 101226_100 / carib-0011 等编号检索：
    // 规范化键 `label|num`（num 去前导零，hez9=hez-009；fc2 开头去尾部 ppv，FC2-X=FC2PPV-X 同族兼容），
    // 番号型查询改走标题提取精确匹配，绕开 isRelevant 数字边界完整规则对前导零/连字符的失配。

    private val CODE_SEP = Regex("[\\s\\-_·]")
    private val DATE_T = Regex("(?<!\\d)\\d{6}[-_]\\d{2,3}(?!\\d)")
    /** 带分隔写法：HEZ-009 / FC2-PPV-1921054 / HEZ 009（label 可含连字符字母块） */
    private val CODE_T_SEP = Regex(
        "\\b([a-z][a-z0-9]*(?:[-_][a-z]{1,6})*)[-_ ]+(\\d{2,7})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    /** 粘连写法：HEZ009 / CARIB0011——label 必须以字母收尾，杜绝贪婪回溯把编号首位零切进厂牌（hez0|9） */
    private val CODE_T_FLAT = Regex(
        "\\b([a-z](?:[a-z0-9]*[a-z])?)(\\d{2,7})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    /** 画质/封装格式词不是厂牌，提取与查询双侧都排除（防「HD 1080」误提） */
    private val CODE_STOP = setOf(
        "hd", "sd", "fhd", "uhd", "xhd", "mp4", "mkv", "avi", "mp3", "rmvb", "rm",
        "flv", "mov", "webm", "m4v", "x264", "x265", "dvd", "vcd", "ch", "ep",
    )

    /** 查询 → 番号规范键；非编号形态返回 null（调用方回落常规匹配） */
    fun codeCanon(q: String): String? {
        val s = q.trim().lowercase()
        if (s.isEmpty() || !Regex("^[a-z0-9][a-z0-9 \\-_·]*$").matches(s)) return null
        var label: String? = null
        var num: String? = null
        val sep = Regex("^(.+?)[ \\-_·]+(\\d{1,7})$").matchEntire(s)
        if (sep != null) {
            label = sep.groupValues[1]; num = sep.groupValues[2]
        } else {
            val flat = s.replace(CODE_SEP, "")
            Regex("^(\\d{6})(\\d{2,3})$").matchEntire(flat)?.let {
                label = it.groupValues[1]; num = it.groupValues[2]
            } ?: Regex("^([a-z]+)(\\d{1,7})$").matchEntire(flat)?.let {
                label = it.groupValues[1]; num = it.groupValues[2]
            } ?: Regex("^([a-z][a-z0-9]*?)(\\d{2,7})$").matchEntire(flat)?.let {
                label = it.groupValues[1]; num = it.groupValues[2]
            }
        }
        val l = label?.replace(CODE_SEP, "") ?: return null
        if (num == null || l.length > 12) return null
        val okLabel = l.matches(Regex("[a-z][a-z0-9]*")) || l.matches(Regex("\\d{6}"))
        if (!okLabel) return null
        var lab = if (l.startsWith("fc2")) l.removeSuffix("ppv") else l
        if (lab in CODE_STOP) return null
        val n = num.trimStart('0')
        if (n.isEmpty()) return null
        return "$lab|$n"
    }

    /** 标题提取全部番号：规范键 → 原文（徽章/词库用原文）；LinkedHashMap 保首个=最像编号的一处 */
    fun extractCodes(name: String): List<Pair<String, String>> {
        if (name.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, String>()
        for (m in DATE_T.findAll(name)) {
            val p = m.value.split(Regex("[-_]"))
            val n = p[1].trimStart('0')
            if (n.isNotEmpty()) out.putIfAbsent("${p[0]}|$n", m.value)
        }
        for (rx in listOf(CODE_T_SEP, CODE_T_FLAT)) for (m in rx.findAll(name)) {
            var l = m.groupValues[1].lowercase().replace(CODE_SEP, "")
            if (l.length > 12 || !l.matches(Regex("[a-z][a-z0-9]*"))) continue
            if (l.startsWith("fc2")) l = l.removeSuffix("ppv")
            if (l in CODE_STOP) continue
            val n = m.groupValues[2].trimStart('0')
            if (n.isNotEmpty()) out.putIfAbsent("$l|$n", m.value)
        }
        return out.toList()
    }

    /** 标题首个番号原文（结果行徽章、词库种入用） */
    fun codeRaw(name: String): String? = extractCodes(name).firstOrNull()?.second

    /** 标题是否含指定规范键番号 */
    fun codeMatch(name: String, canon: String): Boolean = extractCodes(name).any { it.first == canon }

    /** 搜索相关性入口：番号形态查询走提取精确匹配，另兜底严格常规匹配（标题编号写法过糙提不出时不丢命中）；其余回落 isRelevant（web 语义不变） */
    fun relevantForSearch(name: String, wd: String, fuzzy: Boolean = true): Boolean {
        codeCanon(wd)?.let { c -> return codeMatch(name, c) || isRelevant(name, wd, false) }
        return isRelevant(name, wd, fuzzy)
    }

    /** ac=list 返回的 class 数组 → 分类列表 */
    fun parseTypes(data: JSONObject): List<VodType> {
        val arr = data.optJSONArray("class") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id")
            val name = o.optString("type_name")
            if (id.isEmpty() || name.isEmpty()) null
            else VodType(id, name, o.optString("type_pid"), parseExtend(o.opt("type_extend")))
        }
    }

    /** web parseExtend 移植：对象 | JSON 串 | "k,v1,v2;k2,…" 分号串三种形态 → key→逗号串 */
    fun parseExtend(v: Any?): Map<String, String> {
        if (v == null || v == JSONObject.NULL) return emptyMap()
        when (v) {
            is JSONObject -> return v.keys().asSequence().associateWith { k -> joinVal(v.opt(k)) }
            is JSONArray -> return emptyMap()
        }
        val s = v.toString()
        if (s.isBlank()) return emptyMap()
        runCatching { return parseExtend(JSONObject(s)) }
        val out = LinkedHashMap<String, String>()
        s.split(';').forEach { kv ->
            val i = kv.indexOf(',')
            if (i > 0) out[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
        return out
    }

    private fun joinVal(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString(",") { v.opt(it)?.toString() ?: "" }
        is JSONObject, null, JSONObject.NULL -> ""
        else -> v.toString()
    }.let { it.split(",").filter { x -> x.isNotBlank() }.joinToString(",") }

    /** web parseFilterMeta 移植：ac=videolist 响应 class[] 里首个带 area/year/lang 的 extend（部分源 ac=list 不带） */
    fun parseFilterMeta(data: JSONObject): Map<String, String>? {
        val arr = data.optJSONArray("class") ?: return null
        for (i in 0 until arr.length()) {
            val e = parseExtend(arr.optJSONObject(i)?.opt("type_extend"))
            if (e.containsKey("area") || e.containsKey("year") || e.containsKey("lang")) return e
        }
        return null
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

    /** 源1$$$源2，集1$URL1#集2$URL2 → 保留每条线路（flag+剧集），对齐 web 多线路结构；
     *  flag 优先取 vod_play_from 第 i 段，其次组内「flag:」前缀（冒号须在首个 http 前，防切进协议头） */
    fun extractLinesFromPlayUrl(playUrl: String, playFrom: String = ""): List<PlayLine> {
        if (playUrl.isBlank()) return emptyList()
        // 线路分隔符为三个美元符；"$$" 在 Kotlin 中是转义，需显式拼接
        val fromFlags = playFrom.split("$".repeat(3)).map { it.trim() }
        return playUrl.split("$".repeat(3)).mapIndexedNotNull { gi, group ->
            var flag = fromFlags.getOrNull(gi).orEmpty()
            var body = group
            if (flag.isEmpty()) {
                val ci = group.indexOf(':')
                val hi = group.indexOf("http")
                if (ci >= 0 && (hi < 0 || ci < hi)) {
                    flag = group.substring(0, ci).trim()
                    body = group.substring(ci + 1)
                }
            }
            val eps = body.split("#").mapIndexedNotNull { idx, ep ->
                val parts = ep.split("$")
                val url = if (parts.size > 1) parts[1].trim() else ""
                if (url.startsWith("http://") || url.startsWith("https://"))
                    PlayEpisode(parts[0].trim().ifBlank { "第${idx + 1}集" }, url)
                else null
            }
            if (eps.isEmpty()) null else PlayLine(flag.ifBlank { "默认线路" }, eps)
        }
    }

    fun extractM3u8LinesFromText(text: String): List<PlayLine> {
        if (text.isBlank()) return emptyList()
        val m = M3U8_IN_TEXT.matcher(text)
        val out = mutableListOf<PlayEpisode>()
        while (m.find()) out.add(PlayEpisode("简介直链", m.group().removePrefix("$")))
        return if (out.isEmpty()) emptyList() else listOf(PlayLine("简介直链", out))
    }

    fun parseDetail(data: JSONObject, source: VodSource): VideoDetail? {
        val arr = data.optJSONArray("list")
        val vod = arr?.optJSONObject(0) ?: return null
        var lines = extractLinesFromPlayUrl(vod.optString("vod_play_url"), vod.optString("vod_play_from"))
        if (lines.isEmpty()) lines = extractM3u8LinesFromText(vod.optString("vod_content"))
        return VideoDetail(
            title = vod.optString("vod_name"),
            cover = vod.optString("vod_pic").ifBlank { null },
            desc = vod.optString("vod_content").ifBlank { null },
            typeName = vod.optString("type_name").ifBlank { null },
            year = vod.optString("vod_year").ifBlank { null },
            area = vod.optString("vod_area").ifBlank { null },
            actor = vod.optString("vod_actor").ifBlank { null },
            director = vod.optString("vod_director").ifBlank { null },
            remarks = (vod.optString("vod_remarks").ifBlank { vod.optString("vod_notes") }).ifBlank { null },
            sourceName = source.name,
            lines = lines,
        )
    }
}
