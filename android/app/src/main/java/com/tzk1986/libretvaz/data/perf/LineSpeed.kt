package com.tzk1986.libretvaz.data.perf

import com.tzk1986.libretvaz.data.DiskBacked
import com.tzk1986.libretvaz.data.Http
import com.tzk1986.libretvaz.data.PlayLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A1 线路吞吐采样（对齐 web fongmi.js sampleKbps/lineSpeed/LINE_SPEED）：
 * Range 0-131071 限 128KB 成本；源站不支持 Range 则全速读、2.5s 预算到点即断同样得 KB/s；
 * m3u8 先拉清单取首分片采样（分片比清单文本更贴近真实带宽）。
 * 内存工作表 + 6h 磁盘回灌（H5，web fongmi_lspeed 等价）：历史/收藏再进详情页直接复用旧速度与旧快线；
 * 仅测出有效值（含 0/-1 判定的终表）才落盘，进行中的半截表只留内存。0=失败、-1=采样失败但播放中（不判死）。
 */
object LineSpeed {
    const val CONCURRENCY = 4
    const val MAX_LINES = 10
    const val DISK_TTL = 6 * 3600_000L          // 6h 持久复用窗口（web fongmi_lspeed.t 判定同值）
    private const val BUDGET_MS = 2500L

    private val cache = ConcurrentHashMap<String, List<Long?>>()
    private val disk = DiskBacked(
        MemCache<List<Long?>>(256), "ls:",
        { v -> JSONArray().apply { v.forEach { if (it == null) put(JSONObject.NULL) else put(it) } }.toString() },
        { s -> runCatching { val a = JSONArray(s); (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) } }.getOrNull() },
    )
    private val M3U8_RE = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE)

    fun key(sourceKey: String, vodId: String, ep: Int): String = "$sourceKey|$vodId|$ep"

    /** 读缓存：内存优先，未中回灌 6h 内磁盘结果（web speedProbe 前 LINE_SPEED 命中语义 + paint 复用 pe.sp） */
    fun cached(key: String): List<Long?>? {
        cache[key]?.let { return it }
        val v = disk.get(key, DISK_TTL) ?: return null
        cache[key] = v
        return v
    }

    fun put(key: String, speeds: List<Long?>) {
        cache[key] = speeds
        if (speeds.any { it != null }) disk.set(key, speeds)
    }

    fun delete(key: String) { cache.remove(key); disk.remove(key) }

    fun clearAll() { cache.clear(); disk.clear() }

    /**
     * 并发（4）测本作品本集前 ≤10 线：完成一线即回调 onLine（全表快照，UI 原位刷新）；
     * 返回最终全表。已缓存直接返回缓存（同 web「本作品本集已测/在测不重测」）。
     */
    suspend fun measure(
        key: String,
        lines: List<PlayLine>,
        ep: Int,
        currentLine: Int,
        playingHealthy: () -> Boolean,
        onLine: (List<Long?>) -> Unit,
    ): List<Long?> = coroutineScope {
        cached(key)?.let { return@coroutineScope it }
        val n = minOf(lines.size, MAX_LINES)
        val sp = arrayOfNulls<Long>(n)
        cache[key] = sp.toList()
        onLine(sp.toList())
        val sem = Semaphore(CONCURRENCY)
        val jobs = (0 until n).map { i ->
            launch(Dispatchers.IO) {
                sem.withPermit {
                    val u = lines[i].list.getOrNull(ep) ?: lines[i].list.firstOrNull()
                    var v = 0L
                    if (u != null && u.link.isNotEmpty()) {
                        v = try { sampleKbps(segOrSelf(u.link)) } catch (e: Exception) { 0L }
                    }
                    // 当前线测 0 但播放器实际流畅→重分类为「测速失败」，显示层以播放为准
                    if (v == 0L && i == currentLine && playingHealthy()) v = -1L
                    sp[i] = v
                    cache[key] = sp.toList()
                    onLine(sp.toList())
                }
            }
        }
        jobs.forEach { it.join() }
        val finalSp = sp.toList()
        if (finalSp.any { it != null }) disk.set(key, finalSp) // 终表落盘 6h（web speedProbe.then 持久化等价）
        finalSp
    }

    /** 线路地址→采样目标：m3u8 取清单首个分片（无分片视为失败，对齐 web「清单无分片」抛错），其余直接采样 */
    fun segOrSelf(link: String): String {
        if (!M3U8_RE.containsMatchIn(link)) return link
        val text = fetchText(link)
        val first = text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: throw IllegalStateException("清单无分片")
        return try { URL(URL(link), first).toExternalForm() } catch (e: Exception) { first }
    }

    /** 单地址采样吞吐（KB/s）：掐表/异常前已收到字节按已收部分计速 */
    fun sampleKbps(u: String, budgetMs: Long = BUDGET_MS): Long {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        val conn = openGet(u, "bytes=0-131071", 3000)
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    bytes += r
                    if (System.currentTimeMillis() - t0 >= budgetMs) break
                }
            }
        } catch (e: Exception) {
            if (bytes == 0L) throw e
        } finally {
            conn.disconnect()
        }
        if (bytes == 0L) return 0L
        val ms = maxOf(1L, System.currentTimeMillis() - t0)
        return bytes * 1000 / ms / 1024
    }

    private fun fetchText(u: String, timeoutMs: Int = 8000): String {
        val conn = openGet(u, null, timeoutMs)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 带跨协议重定向跟随的 GET（http↔https CDN 常见）；代理与 Http.get 同源（Http.proxySpec） */
    private fun openGet(u: String, range: String?, timeoutMs: Int): HttpURLConnection {
        val proxy = Http.proxySpec()?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it.first, it.second)) }
        var cur = u
        repeat(6) {
            val c = (if (proxy != null) URL(cur).openConnection(proxy) else URL(cur).openConnection()) as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "GET"
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Http.CHROME_UA)
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            when {
                code in 200..299 -> return c
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrEmpty()) throw IllegalStateException("HTTP $code")
                    cur = try { URL(URL(cur), loc).toString() } catch (e: Exception) { loc }
                }
                else -> {
                    c.disconnect()
                    throw IllegalStateException("HTTP $code")
                }
            }
        }
        throw IllegalStateException("重定向次数过多")
    }
}
