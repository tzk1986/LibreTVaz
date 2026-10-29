package com.tzk1986.libretvaz.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.regex.Pattern

/**
 * 订阅配置解析（移植自 Web 端 tvbox-parser.ts / source-list.ts）：
 * 自动识别 TVBOX 配置 / LibreTV-SourceList / 接口目录三种格式，容错注释与尾随逗号。
 */
data class ParsedSubscription(
    val name: String?,
    val vod: List<VodSource>,
    val live: List<LiveSource>,
    val skipped: Int,
    val format: String,
)

object SubscriptionParser {
    private const val MAX_VOD = 100
    private const val MAX_LIVE = 50
    private val SPIDER_PATTERN = Pattern.compile("^csp_|\\.(?:jar|js|py)(?:[?#].*)?$", Pattern.CASE_INSENSITIVE)
    private val CMS_API_PATTERN =
        Pattern.compile("/api\\.php/provide/vod(?![^?#]*/at/xml)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE)
    private val M3U_PATTERN = Pattern.compile("\\.m3u8?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE)
    private val TXT_PATTERN = Pattern.compile("\\.txt(?:[?#].*)?$", Pattern.CASE_INSENSITIVE)
    private val HTTP_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE)

    fun parse(text: String): ParsedSubscription {
        val cleaned = sanitizeJson(text.removePrefix("﻿"))
        val root = try {
            JSONTokener(cleaned).nextValue()
        } catch (e: Exception) {
            throw IllegalArgumentException("订阅内容不是合法的 JSON")
        }
        return when {
            root is JSONObject && (root.optJSONArray("sites") != null || root.optJSONArray("lives") != null) ->
                parseTvbox(root)
            root is JSONArray && root.length() > 0 && allCatalogEntries(root) -> parseCatalog(root)
            root is JSONArray && root.length() > 0 && someCatalogEntries(root) ->
                throw IllegalArgumentException("混用了 baseUrl 与 url 两种条目，请统一格式")
            else -> parseSourceList(root)
        }
    }

    /** 剥离字符串外的行注释与块注释、删除尾随逗号、转义字符串内裸控制字符 */
    private fun sanitizeJson(input: String): String {
        val stripped = stripCommentsAndFixControl(input)
        return stripTrailingCommas(stripped)
    }

    private fun stripCommentsAndFixControl(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var inLine = false
        var inBlock = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inLine -> {
                    if (ch == '\n') { inLine = false; out.append(ch) }
                }
                inBlock -> if (ch == '*' && next == '/') { inBlock = false; i++ }
                inString -> {
                    if (ch == '\\') { out.append(ch).append(next); i++ }
                    else if (ch == '"') { inString = false; out.append(ch) }
                    else if (ch.code < 0x20) {
                        when (ch) {
                            '\n' -> out.append("\\n")
                            '\r' -> out.append("\\r")
                            '\t' -> out.append("\\t")
                            else -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                        }
                    } else out.append(ch)
                }
                else -> when {
                    ch == '"' -> { inString = true; out.append(ch) }
                    ch == '/' && next == '/' -> { inLine = true; i++ }
                    ch == '/' && next == '*' -> { inBlock = true; i++ }
                    else -> out.append(ch)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun stripTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                out.append(ch)
                if (ch == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ }
                else if (ch == '"') inString = false
            } else if (ch == '"') {
                inString = true; out.append(ch)
            } else if (ch == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) { /* 丢尾逗号 */ }
                else out.append(ch)
            } else out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun normalizeUrl(raw: Any?, trimTrailingSlash: Boolean): String? {
        val trimmed = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!HTTP_PREFIX.matcher(trimmed).find()) return null
        var u = trimmed.replace(Regex("[?#]+$"), "")
        if (trimTrailingSlash) u = u.replace(Regex("/+$"), "")
        return u.takeIf { it.isNotEmpty() }
    }

    private fun optionalString(raw: Any?): String? =
        (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun toNumber(raw: Any?): Int? = when (raw) {
        is Int -> raw
        is Long -> raw.toInt()
        is Double -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    private fun allCatalogEntries(arr: JSONArray): Boolean =
        (0 until arr.length()).all { (arr.opt(it) as? JSONObject)?.has("baseUrl") == true }

    private fun someCatalogEntries(arr: JSONArray): Boolean =
        (0 until arr.length()).any { (arr.opt(it) as? JSONObject)?.has("baseUrl") == true }

    private class Skips { var count = 0 }

    private fun parseTvbox(root: JSONObject): ParsedSubscription {
        val skips = Skips()
        val vod = collectSites(root.optJSONArray("sites") ?: JSONArray(), skips)
        val live = collectLives(root.optJSONArray("lives") ?: JSONArray(), skips)
        if (vod.isEmpty() && live.isEmpty()) {
            throw IllegalArgumentException(
                if (skips.count > 0) "配置中没有可直接导入的源：已跳过 ${skips.count} 个条目（多为 Spider/XML）"
                else "配置中没有可用的站点（sites / lives 均为空）"
            )
        }
        return ParsedSubscription(optionalString(root.opt("name")), vod, live, skips.count, "tvbox")
    }

    private fun collectSites(sites: JSONArray, skips: Skips): List<VodSource> {
        val seen = HashSet<String>()
        val out = ArrayList<VodSource>()
        for (i in 0 until sites.length()) {
            val site = sites.optJSONObject(i) ?: continue
            val name = optionalString(site.opt("name"))
            val api = optionalString(site.opt("api"))
            val type = toNumber(site.opt("type"))
            if (api == null) { skips.count++; continue }
            if (type == 3 || SPIDER_PATTERN.matcher(api).find()) { skips.count++; continue }
            if (!HTTP_PREFIX.matcher(api).find()) { skips.count++; continue }
            if (site.opt("searchable") == 0 || site.opt("searchable") == "0") { skips.count++; continue }
            val importable = type == 1 ||
                (type == null || type == 0 || type == 4) && CMS_API_PATTERN.matcher(api).find()
            if (!importable) { skips.count++; continue }
            val url = normalizeUrl(api, true) ?: run { skips.count++; continue }
            if (!seen.add(url)) continue
            if (out.size >= MAX_VOD) break
            out.add(VodSource(name ?: hostnameOf(url), url))
        }
        return out
    }

    private fun collectLives(lives: JSONArray, skips: Skips): List<LiveSource> {
        val seen = HashSet<String>()
        val out = ArrayList<LiveSource>()
        for (i in 0 until lives.length()) {
            val live = lives.optJSONObject(i) ?: continue
            val name = optionalString(live.opt("name"))
            val url = normalizeUrl(live.opt("url"), false) ?: run { skips.count++; continue }
            val type = toNumber(live.opt("type"))
            if ((type != null && type != 0) || (!M3U_PATTERN.matcher(url).find() && TXT_PATTERN.matcher(url).find())) {
                skips.count++; continue
            }
            if (!seen.add(url)) continue
            if (out.size >= MAX_LIVE) break
            out.add(LiveSource(name ?: hostnameOf(url), url, normalizeUrl(live.opt("epg"), false)))
        }
        return out
    }

    private fun parseCatalog(arr: JSONArray): ParsedSubscription {
        val seen = HashSet<String>()
        val vod = ArrayList<VodSource>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            if (entry.opt("enabled") == false) { skipped++; continue }
            val url = normalizeUrl(entry.opt("baseUrl"), true) ?: run { skipped++; continue }
            if (!seen.add(url)) continue
            if (vod.size >= MAX_VOD) break
            vod.add(VodSource(optionalString(entry.opt("name")) ?: hostnameOf(url), url))
        }
        if (vod.isEmpty()) throw IllegalArgumentException("接口目录中没有启用且可用的 baseUrl 条目")
        return ParsedSubscription(null, vod, emptyList(), skipped, "source-catalog")
    }

    private fun parseSourceList(root: Any?): ParsedSubscription {
        val asArray = root as? JSONArray
        val obj = if (asArray != null) null else root as? JSONObject
        val rawVod = asArray ?: obj?.optJSONArray("sources") ?: JSONArray()
        val rawLive = obj?.optJSONArray("liveSources") ?: JSONArray()
        if (rawVod.length() == 0 && rawLive.length() == 0) {
            throw IllegalArgumentException("订阅内容格式不正确（缺少 sources / liveSources 数组）")
        }
        val vodSeen = HashSet<String>()
        val vod = ArrayList<VodSource>()
        for (i in 0 until rawVod.length()) {
            if (vod.size >= MAX_VOD) break
            val s = rawVod.optJSONObject(i) ?: continue
            val url = normalizeUrl(s.opt("url"), true) ?: continue
            if (!vodSeen.add(url)) continue
            vod.add(
                VodSource(
                    name = optionalString(s.opt("name")) ?: hostnameOf(url),
                    url = url,
                    detail = normalizeUrl(s.opt("detail"), false),
                    isAdult = s.opt("isAdult") == true,
                )
            )
        }
        val liveSeen = HashSet<String>()
        val live = ArrayList<LiveSource>()
        for (i in 0 until rawLive.length()) {
            if (live.size >= MAX_LIVE) break
            val s = rawLive.optJSONObject(i) ?: continue
            val url = normalizeUrl(s.opt("url"), false) ?: continue
            if (!liveSeen.add(url)) continue
            live.add(LiveSource(optionalString(s.opt("name")) ?: hostnameOf(url), url, normalizeUrl(s.opt("epg"), false)))
        }
        return ParsedSubscription(obj?.let { optionalString(it.opt("name")) }, vod, live, 0, "libretv")
    }

    private fun hostnameOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')
}
