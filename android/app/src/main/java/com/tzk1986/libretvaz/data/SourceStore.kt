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

    // 用户 2026-11-06 走查 #9：每次读全量 JSONArray 解码 → 按原始 JSON 串 memoize（串变即自然失效，写路径零改动）
    fun vodSources(): List<VodSource> {
        val raw = prefs.getString(KEY_VOD, "[]") ?: "[]"
        val cached = lastListVod
        if (raw == lastRawVod && cached != null) return cached
        val list = decodeVod(raw)
        lastRawVod = raw; lastListVod = list
        return list
    }

    fun liveSources(): List<LiveSource> {
        val raw = prefs.getString(KEY_LIVE, "[]") ?: "[]"
        val cached = lastListLive
        if (raw == lastRawLive && cached != null) return cached
        val list = decodeLive(raw)
        lastRawLive = raw; lastListLive = list
        return list
    }

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

    /** 按 URL 删除（审计 2026-11-11 P0-2）：行闭包捕获源对象身份而非渲染下标，
     *  点击期间 SourceGuard/Zone 归池/订阅导入并发缩表也不再有 removeAt 越界崩 */
    fun removeVodByUrl(url: String) = mutateVod { list ->
        val i = list.indexOfFirst { it.url == url }
        if (i < 0) false else { list.removeAt(i); true }
    }

    /** 整表替换（健康清理死源后用） */
    fun replaceVod(list: List<VodSource>) = mutateVod { l -> l.clear(); l.addAll(list); true }
    /** 按 URL 删除直播源（同 P0-2：闭包捕获身份不吃渲染下标） */
    fun removeLiveByUrl(url: String) = mutateLive { list ->
        val i = list.indexOfFirst { it.url == url }
        if (i < 0) false else { list.removeAt(i); true }
    }

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

    /** raw.githubusercontent 直连失败自动换 gh-proxy 前缀（web A5 doImport getSub 等价）。
     *  E46（审计 2026-11-11）：中转会把订阅 URL 交给第三方（token 型订阅有泄露面），
     *  开关 gh_proxy（SettingsStore，默认开维持现行为）关闭时不再兜底、直连失败即报错 */
    private suspend fun getSub(u: String): String = withContext(Dispatchers.IO) {
        try {
            Http.get(PerfCore.norm(u), timeoutMs = 20000)
        } catch (e: Exception) {
            val ghOn = app.getSharedPreferences("libretvaz", Context.MODE_PRIVATE).getBoolean("gh_proxy", true)
            if (ghOn && Regex("^https://raw\\.githubusercontent\\.com/", RegexOption.IGNORE_CASE).matches(u))
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

    /** 后台维护重拉专用（web refreshSubsIfStale 等价）：前 5 订阅、合并增量、norm 去重、200 封顶。
     *  返回 (新增数, 任一订阅拉取成功)——P1-3：全失败时 guard 落退避戳短周期重试，不再消费满 24h */
    suspend fun refreshSubs(): Pair<Int, Boolean> {
        var added = 0
        var anyOk = false
        subs().take(5).forEach { sub ->
            val parsed = runCatching { SubscriptionParser.parseExpanded(getSub(sub)) { repoUrl -> getSub(repoUrl) } }.getOrNull()
                ?: return@forEach // 订阅暂时不可达，退避后再试
            anyOk = true
            mutateVod { list -> added += mergeVod(list, parsed.vod); true }
        }
        if (added > 0) alignZone()
        return added to anyOk
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
        @Volatile private var lastRawVod: String? = null
        @Volatile private var lastListVod: List<VodSource>? = null
        @Volatile private var lastRawLive: String? = null
        @Volatile private var lastListLive: List<LiveSource>? = null
        private const val KEY_VOD = "vod_sources"
        private const val KEY_LIVE = "live_sources"
        private const val KEY_SUBS = "subs"
        const val MAX_VOD = 200
        private const val MAX_LIVE = 50
    }
}
