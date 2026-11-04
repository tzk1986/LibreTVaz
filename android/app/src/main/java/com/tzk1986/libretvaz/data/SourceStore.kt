package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 多点播源 + 多直播源的本地持久化管理（直连模式的核心配置） */
class SourceStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("libretvaz_sources", Context.MODE_PRIVATE)

    fun vodSources(): List<VodSource> = decodeVod(prefs.getString(KEY_VOD, "[]") ?: "[]")
    fun liveSources(): List<LiveSource> = decodeLive(prefs.getString(KEY_LIVE, "[]") ?: "[]")

    /** 归池收编（web syncSrc→alignZonePool：每次读源前对齐，只升不降） */
    fun alignZone(): Boolean = Zone.align(app, this)

    /** 点播池（常规视图/搜索/测速墙消费）：对齐后取非专区源，专区只在资源口出现 */
    fun vodPool(): List<VodSource> {
        alignZone()
        return vodSources().filterNot { it.isAdult }
    }

    fun addVod(source: VodSource): Boolean {
        val url = normalizeVodUrl(source.url)
        if (!isHttp(url)) return false
        // 源名命中专区分类词（web isAdultType）→ 直接归专区池
        val adult = source.isAdult || Zone.isAdultType(source.name)
        val ok = mutateVod { list ->
            if (list.size >= MAX_VOD || list.any { PerfCore.norm(it.url) == PerfCore.norm(url) }) false
            else { list.add(source.copy(name = source.name.ifBlank { hostname(url) }, url = url, isAdult = adult)); true }
        }
        if (ok) alignZone()
        return ok
    }

    fun addLive(source: LiveSource): Boolean {
        val url = source.url.trim()
        // asset:// 为内置直播种子（B16），仅程序内种入，外部导入仍限 http(s)
        if (!isHttp(url) && !url.startsWith("asset://")) return false
        return mutateLive { list ->
            if (list.size >= MAX_LIVE || list.any { PerfCore.norm(it.url) == PerfCore.norm(url) }) false
            else { list.add(source.copy(name = source.name.ifBlank { hostname(url) }, url = url)); true }
        }
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    fun removeVodAt(index: Int) = mutateVod { list -> list.removeAt(index); true }

    /** 整表替换（健康清理死源后用） */
    fun replaceVod(list: List<VodSource>) = mutateVod { l -> l.clear(); l.addAll(list); true }
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

    /** 订阅链接清单（web SRC.subs 等价，norm 去重；sourceGuard 每 24h 重拉消费） */
    fun subs(): List<String> =
        runCatching { val a = JSONArray(prefs.getString(KEY_SUBS, "[]") ?: "[]"); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())

    private fun addSub(url: String) {
        val u = PerfCore.norm(url)
        val list = subs().toMutableList()
        if (u.isNotBlank() && list.none { it == u }) { list.add(u); prefs.edit().putString(KEY_SUBS, JSONArray(list).toString()).apply() }
    }

    /** raw.githubusercontent 直连失败自动换 gh-proxy 前缀（web A5 doImport getSub 等价） */
    private suspend fun getSub(u: String): String = withContext(Dispatchers.IO) {
        try {
            Http.get(PerfCore.norm(u), timeoutMs = 20000)
        } catch (e: Exception) {
            if (Regex("^https://raw\\.githubusercontent\\.com/", RegexOption.IGNORE_CASE).matches(u))
                Http.get("https://gh-proxy.com/$u", timeoutMs = 20000)
            else throw e
        }
    }

    suspend fun importFromUrl(url: String): ImportResult {
        val trimmed = url.trim().removeSuffix("/")
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) { "请输入 http(s) 配置链接" }
        val text = getSub(trimmed)
        val parsed = SubscriptionParser.parseExpanded(text) { repoUrl -> getSub(repoUrl) }
        var vodAdded = 0
        var liveAdded = 0
        mutateVod { list -> vodAdded = mergeVod(list, parsed.vod); true }
        mutateLive { list -> liveAdded = mergeLive(list, parsed.live); true }
        addSub(trimmed) // 记入订阅清单，供 24h 自动重拉（web SRC.subs push 等价）
        alignZone() // 订阅内命中预置站单的源即收进专区池并去重（web syncSrc 同款）
        return ImportResult(vodAdded, liveAdded, parsed.skipped, parsed.format, parsed.name, parsed.repos, parsed.reposFailed)
    }

    /** 后台维护重拉专用（web refreshSubsIfStale 等价）：前 5 订阅、合并增量、norm 去重、200 封顶，返回新增数 */
    suspend fun refreshSubs(): Int {
        var added = 0
        subs().take(5).forEach { sub ->
            val parsed = runCatching { SubscriptionParser.parseExpanded(getSub(sub)) { repoUrl -> getSub(repoUrl) } }.getOrNull()
                ?: return@forEach // 订阅暂时不可达，下个周期再试
            mutateVod { list -> added += mergeVod(list, parsed.vod); true }
        }
        if (added > 0) alignZone()
        return added
    }

    private fun mergeVod(target: MutableList<VodSource>, incoming: List<VodSource>): Int {
        var added = 0
        for (s in incoming) {
            if (target.size >= MAX_VOD) break
            if (target.none { PerfCore.norm(it.url) == PerfCore.norm(s.url) }) {
                target.add(if (s.isAdult || Zone.isAdultType(s.name)) s.copy(isAdult = true) else s)
                added++
            }
        }
        return added
    }

    private fun mergeLive(target: MutableList<LiveSource>, incoming: List<LiveSource>): Int {
        var added = 0
        for (s in incoming) {
            if (target.size >= MAX_LIVE) break
            if (target.none { PerfCore.norm(it.url) == PerfCore.norm(s.url) }) { target.add(s); added++ }
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
        private const val KEY_SUBS = "subs"
        const val MAX_VOD = 200
        private const val MAX_LIVE = 50
    }
}
