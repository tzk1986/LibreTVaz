package com.tzk1986.libretvaz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 多点播源 + 多直播源的本地持久化管理（直连模式的核心配置） */
class SourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("libretvaz_sources", Context.MODE_PRIVATE)

    fun vodSources(): List<VodSource> = decodeVod(prefs.getString(KEY_VOD, "[]") ?: "[]")
    fun liveSources(): List<LiveSource> = decodeLive(prefs.getString(KEY_LIVE, "[]") ?: "[]")

    fun addVod(source: VodSource): Boolean {
        val url = normalizeVodUrl(source.url)
        if (!isHttp(url)) return false
        return mutateVod { list ->
            if (list.size >= MAX_VOD || list.any { it.url == url }) false
            else { list.add(source.copy(name = source.name.ifBlank { hostname(url) }, url = url)); true }
        }
    }

    fun addLive(source: LiveSource): Boolean {
        val url = source.url.trim()
        if (!isHttp(url)) return false
        return mutateLive { list ->
            if (list.size >= MAX_LIVE || list.any { it.url == url }) false
            else { list.add(source.copy(name = source.name.ifBlank { hostname(url) }, url = url)); true }
        }
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    fun removeVodAt(index: Int) = mutateVod { list -> list.removeAt(index); true }
    fun removeLiveAt(index: Int) = mutateLive { list -> list.removeAt(index); true }

    /** 拉取订阅链接并合并入库；返回导入结果。失败抛异常由调用方展示 */
    data class ImportResult(
        val vodAdded: Int,
        val liveAdded: Int,
        val skipped: Int,
        val format: String,
        val name: String?,
        val repos: Int = 0,
        val reposFailed: Int = 0,
    )

    suspend fun importFromUrl(url: String): ImportResult {
        val trimmed = url.trim().removeSuffix("/")
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) { "请输入 http(s) 配置链接" }
        val text = withContext(Dispatchers.IO) { Http.get(trimmed, timeoutMs = 15000) }
        val parsed = SubscriptionParser.parseExpanded(text) { repoUrl ->
            withContext(Dispatchers.IO) { Http.get(repoUrl, timeoutMs = 15000) }
        }
        var vodAdded = 0
        var liveAdded = 0
        mutateVod { list -> vodAdded = mergeVod(list, parsed.vod); true }
        mutateLive { list -> liveAdded = mergeLive(list, parsed.live); true }
        return ImportResult(vodAdded, liveAdded, parsed.skipped, parsed.format, parsed.name, parsed.repos, parsed.reposFailed)
    }

    private fun mergeVod(target: MutableList<VodSource>, incoming: List<VodSource>): Int {
        var added = 0
        for (s in incoming) {
            if (target.size >= MAX_VOD) break
            if (target.none { it.url == s.url }) { target.add(s); added++ }
        }
        return added
    }

    private fun mergeLive(target: MutableList<LiveSource>, incoming: List<LiveSource>): Int {
        var added = 0
        for (s in incoming) {
            if (target.size >= MAX_LIVE) break
            if (target.none { it.url == s.url }) { target.add(s); added++ }
        }
        return added
    }

    private fun mutateVod(block: (MutableList<VodSource>) -> Boolean): Boolean {
        val list = decodeVod(prefs.getString(KEY_VOD, "[]") ?: "[]").toMutableList()
        return if (block(list)) { prefs.edit().putString(KEY_VOD, encodeVod(list)).apply(); true } else false
    }

    private fun mutateLive(block: (MutableList<LiveSource>) -> Boolean): Boolean {
        val list = decodeLive(prefs.getString(KEY_LIVE, "[]") ?: "[]").toMutableList()
        return if (block(list)) { prefs.edit().putString(KEY_LIVE, encodeLive(list)).apply(); true } else false
    }

    private fun encodeVod(list: List<VodSource>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("name", s.name); put("url", s.url)
                s.detail?.let { put("detail", it) }
                if (s.isAdult) put("isAdult", true)
            })
        }
    }.toString()

    private fun encodeLive(list: List<LiveSource>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("name", s.name); put("url", s.url)
                s.epg?.let { put("epg", it) }
            })
        }
    }.toString()

    private fun decodeVod(json: String): List<VodSource> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) return@mapNotNull null
            VodSource(o.optString("name").ifBlank { hostname(url) }, url, o.optString("detail").ifBlank { null }, o.optBoolean("isAdult"))
        }
    }.getOrDefault(emptyList())

    private fun decodeLive(json: String): List<LiveSource> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) return@mapNotNull null
            LiveSource(o.optString("name").ifBlank { hostname(url) }, url, o.optString("epg").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    private fun normalizeVodUrl(url: String): String =
        url.trim().removeSuffix("/").ifEmpty { url.trim() }

    private fun hostname(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')

    companion object {
        private const val KEY_VOD = "vod_sources"
        private const val KEY_LIVE = "live_sources"
        const val MAX_VOD = 200
        private const val MAX_LIVE = 50
    }
}
