package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 直连模式搜索/详情仓储：多源并行，单源失败或超时不影响整体 */
class CmsRepository {

    data class SearchOutcome(val list: List<VodSummary>, val errors: List<String>)

    /** 每源最多 5 页、10s 总死线，与 Web 端 SEARCH_MAX_PAGES / SEARCH_SOURCE_TIMEOUT_MS 默认值对齐 */
    suspend fun search(
        wd: String,
        sources: List<VodSource>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): SearchOutcome = coroutineScope {
        val done = AtomicInteger(0)
        val errors = ConcurrentHashMap<Int, String>()
        val perSource = sources.mapIndexed { index, source ->
            async(Dispatchers.IO) {
                val items = runCatching {
                    withTimeout(SOURCE_TIMEOUT_MS) { searchOneSource(source, wd) }
                }.getOrElse { e ->
                    errors[index] = if (e is TimeoutCancellationException) "请求超时"
                    else e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                onProgress?.invoke(done.incrementAndGet(), sources.size)
                items
            }
        }.awaitAll()

        val errorMessages = perSource.indices.mapNotNull { i -> errors[i]?.let { "${sources[i].name}：$it" } }
        var filtered = perSource.flatten().distinctBy { it.sourceKey + it.vodId }
        filtered = filtered.filter { CmsParser.isRelevant(it.name, wd) }
        val exact = CmsParser.normalizeTitle(wd)
        filtered = filtered.sortedWith(
            compareBy(
                { if (CmsParser.normalizeTitle(it.name) == exact) 0 else 1 },
                { it.name },
                { it.sourceName },
            )
        )
        SearchOutcome(filtered, errorMessages)
    }

    /** 单源搜索仅第 1 页（web Collect 一源一次 ac=videolist&wd=&pg=1 等价，交给上层并发） */
    suspend fun searchSourcePage1(source: VodSource, wd: String): List<VodSummary> = withContext(Dispatchers.IO) {
        val query = "ac=videolist&wd=${URLEncoder.encode(wd, "UTF-8")}&pg=1"
        CmsParser.parseSearchList(fetchJson(apiUrl(source, query)), source)
    }

    private suspend fun searchOneSource(source: VodSource, wd: String): List<VodSummary> {
        val query = "ac=videolist&wd=${URLEncoder.encode(wd, "UTF-8")}"
        val firstJson = fetchJson(apiUrl(source, "$query&pg=1"))
        val list = CmsParser.parseSearchList(firstJson, source).toMutableList()
        val pageCount = firstJson.optString("pagecount", "1").toIntOrNull()?.coerceIn(1, MAX_PAGES) ?: 1
        if (pageCount > 1) {
            val extras = coroutineScope {
                (2..pageCount).map { pg ->
                    async(Dispatchers.IO) {
                        runCatching { CmsParser.parseSearchList(fetchJson(apiUrl(source, "$query&pg=$pg")), source) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            extras.forEach { list.addAll(it) }
        }
        return list
    }

    /** 首页"最近更新"：带 ac=videolist 取完整字段——无参模式多数源不返回 vod_pic，海报墙会全空白 */
    suspend fun home(source: VodSource, page: Int): List<VodSummary> = withContext(Dispatchers.IO) {
        runCatching {
            CmsParser.parseSearchList(fetchJson(apiUrl(source, "ac=videolist&pg=$page")), source)
        }.getOrDefault(emptyList())
    }

    /** ac=list 取源分类（影视首页导航用） */
    suspend fun types(source: VodSource): List<VodType> = withContext(Dispatchers.IO) {
        runCatching { CmsParser.parseTypes(fetchJson(apiUrl(source, "ac=list"))) }.getOrDefault(emptyList())
    }

    /** 按分类翻列表页，ac=videolist&t={typeId}&pg= */
    suspend fun listByType(source: VodSource, typeId: String, page: Int): List<VodSummary> =
        listFiltered(source, typeId, "", "", "", page).first

    /**
     * 二级筛选翻页（web loadVod L1645-1652 等价）：t=子类覆盖主分类，
     * area/year/lang 仅在有分类时拼接；响应 JSON 一并回传供 parseFilterMeta 取扩展值。
     */
    suspend fun listFiltered(
        source: VodSource, typeId: String, area: String, year: String, lang: String, page: Int,
    ): Pair<List<VodSummary>, JSONObject?> = withContext(Dispatchers.IO) {
        val sb = StringBuilder("ac=videolist&t=").append(URLEncoder.encode(typeId, "UTF-8"))
        if (area.isNotEmpty()) sb.append("&area=").append(URLEncoder.encode(area, "UTF-8"))
        if (year.isNotEmpty()) sb.append("&year=").append(URLEncoder.encode(year, "UTF-8"))
        if (lang.isNotEmpty()) sb.append("&lang=").append(URLEncoder.encode(lang, "UTF-8"))
        sb.append("&pg=").append(page)
        runCatching {
            val json = fetchJson(apiUrl(source, sb.toString()))
            CmsParser.parseSearchList(json, source) to json
        }.getOrDefault(emptyList<VodSummary>() to null)
    }

    /** 拉详情并写 SWR 分片（web cacheSet(dk, d) 等价）；失败返回 null，旧缓存由调用方自行回读 */
    suspend fun detail(source: VodSource, id: String): VideoDetail? = withContext(Dispatchers.IO) {
        runCatching {
            CmsParser.parseDetail(fetchJson(apiUrl(source, "ac=videolist&ids=${URLEncoder.encode(id, "UTF-8")}")), source)
                ?.also { DetailCache.store(source, id, it) }
        }.getOrNull()
    }

    /** 详情缓存回读（不限新鲜度，web cacheGet 语义：旧数据也先上屏秒显） */
    fun detailCached(source: VodSource, id: String): VideoDetail? = DetailCache.peek(source, id)

    /** 部分订阅的 api 自带查询串（如 …vod?ac=list），拼接需按是否已含 '?' 选分隔符，否则产生坏 URL */
    private fun apiUrl(source: VodSource, query: String): String {
        val base = source.url.trim().removeSuffix("/").removeSuffix("?")
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    /** 在途 GET 合并（web createDedupe 等价，⚠️6）：同 URL 并发只发一次请求，结果/异常广播给全部等待者 */
    private suspend fun fetchJson(url: String): JSONObject = jsonDedupe.run(url) { loadJson(url) }

    private fun loadJson(url: String): JSONObject = try {
        JSONObject(Http.get(url))
    } catch (e: Exception) {
        // 只记未知错误；超时/重置/HTTP 状态码等非 2xx/坏 JSON（源挂）属可预期，不进日志（用户 2026-11-03）
        val expected = ErrLog.isExpectedNet(e) ||
            (e is IllegalStateException && (e.message?.startsWith("HTTP ") == true || e.message == "too many redirects")) ||
            e is org.json.JSONException
        if (!expected) ErrLog.add("cms", "$url → ${e.javaClass.simpleName}: ${e.message}")
        throw e
    }

    companion object {
        private const val MAX_PAGES = 5
        private const val SOURCE_TIMEOUT_MS = 10_000L
        // 静态共享：各屏各自 new CmsRepository，去重槽必须跨实例才合并在途请求
        private val jsonDedupe = com.tzk1986.libretvaz.data.perf.KeyDedupe<JSONObject>()
    }
}

/** 详情 SWR 双层缓存（web `detail:<normUrl>|<id>` 分片等价）：旧数据无 TTL 先上屏，拉新成功覆盖 */
object DetailCache {
    private val c = DiskBacked(MemCache<VideoDetail>(64), "detail:", CmsCodecs::encDetail, CmsCodecs::decDetail)
    private fun key(source: VodSource, id: String) = "detail:" + PerfCore.norm(source.url) + "|$id"

    fun peek(source: VodSource, id: String): VideoDetail? = c.getRaw(key(source, id))?.v
    fun store(source: VodSource, id: String, d: VideoDetail) = c.set(key(source, id), d)
    fun clearAll() = c.clear()
}
