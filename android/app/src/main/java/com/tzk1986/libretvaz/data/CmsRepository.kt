package com.tzk1986.libretvaz.data

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

    /** 首页"最近更新"：与 TVBox 一致，直接 GET 站点 api 根地址不带参数，取源返回的 list */
    suspend fun home(source: VodSource, page: Int): List<VodSummary> = withContext(Dispatchers.IO) {
        runCatching {
            CmsParser.parseSearchList(fetchJson(apiUrl(source, "pg=$page")), source)
        }.getOrDefault(emptyList())
    }

    /** ac=list 取源分类（影视首页导航用） */
    suspend fun types(source: VodSource): List<VodType> = withContext(Dispatchers.IO) {
        runCatching { CmsParser.parseTypes(fetchJson(apiUrl(source, "ac=list"))) }.getOrDefault(emptyList())
    }

    /** 按分类翻列表页，ac=videolist&t={typeId}&pg= */
    suspend fun listByType(source: VodSource, typeId: String, page: Int): List<VodSummary> = withContext(Dispatchers.IO) {
        runCatching {
            CmsParser.parseSearchList(
                fetchJson(apiUrl(source, "ac=videolist&t=${URLEncoder.encode(typeId, "UTF-8")}&pg=$page")),
                source,
            )
        }.getOrDefault(emptyList())
    }

    suspend fun detail(source: VodSource, id: String): VideoDetail? = withContext(Dispatchers.IO) {
        runCatching {
            CmsParser.parseDetail(fetchJson(apiUrl(source, "ac=videolist&ids=${URLEncoder.encode(id, "UTF-8")}")), source)
        }.getOrNull()
    }

    /** 部分订阅的 api 自带查询串（如 …vod?ac=list），拼接需按是否已含 '?' 选分隔符，否则产生坏 URL */
    private fun apiUrl(source: VodSource, query: String): String {
        val base = source.url.trim().removeSuffix("/").removeSuffix("?")
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    private fun fetchJson(url: String): JSONObject = JSONObject(Http.get(url))

    companion object {
        private const val MAX_PAGES = 5
        private const val SOURCE_TIMEOUT_MS = 10_000L
    }
}
