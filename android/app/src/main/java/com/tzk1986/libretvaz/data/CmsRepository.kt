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
        filterAdult: Boolean = true,
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
        if (filterAdult) {
            filtered = filtered.filter { !it.isAdult && !CmsParser.isAdultType(it.typeName) }
        }
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
        val base = source.url.removeSuffix("/")
        val query = "ac=videolist&wd=${URLEncoder.encode(wd, "UTF-8")}"
        val firstJson = fetchJson("$base?$query&pg=1")
        val list = CmsParser.parseSearchList(firstJson, source).toMutableList()
        val pageCount = firstJson.optString("pagecount", "1").toIntOrNull()?.coerceIn(1, MAX_PAGES) ?: 1
        if (pageCount > 1) {
            val extras = coroutineScope {
                (2..pageCount).map { pg ->
                    async(Dispatchers.IO) {
                        runCatching { CmsParser.parseSearchList(fetchJson("$base?$query&pg=$pg"), source) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            extras.forEach { list.addAll(it) }
        }
        return list
    }

    suspend fun detail(source: VodSource, id: String): VideoDetail? = withContext(Dispatchers.IO) {
        val base = source.url.removeSuffix("/")
        runCatching {
            CmsParser.parseDetail(fetchJson("$base?ac=videolist&ids=${URLEncoder.encode(id, "UTF-8")}"), source)
        }.getOrNull()
    }

    private fun fetchJson(url: String): JSONObject = JSONObject(Http.get(url))

    companion object {
        private const val MAX_PAGES = 5
        private const val SOURCE_TIMEOUT_MS = 10_000L
    }
}
