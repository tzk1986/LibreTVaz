package com.tzk1986.libretvaz.data.perf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A1 线路吞吐采样（对齐 web fongmi.js sampleKbps/lineSpeed/LINE_SPEED）：
 * Range 0-131071 限 128KB 成本；源站不支持 Range 则全速读、2.5s 预算到点即断同样得 KB/s；
 * m3u8 先拉清单取首分片采样（分片比清单文本更贴近真实带宽）。
 * 详情页线路测速已改多切片终值采样 probeAndSample（用户 2026-11-05 明示偏离 web：单片 128KB 不准），
 * 并按用户 2026-11-05 批准的六项优化再改造（调研 fast.com/NDT、media3 SlidingPercentile、IPTV_Test_Speed）：
 *  A2 两阶段——连通/TTFB 快检（800ms 超预算判死活退），慢握手不再污染带宽值；
 *  A1 暖机剔除——每连接丢弃前 1.2s TCP 慢启动样本，只用稳态段算速；样本窗口过短则回退原值（宁可少测不可测歪）；
 *  A3 分位聚合——分片按轮采样，终值取各轮速率中位数，单轮 CDN 打嗝不拉歪整线；
 *  B2 片内并行——每轮 2 片并发（轮间仍串行），同预算采到更多字节；
 *  A4 码率参照——顺带解析 BANDWIDTH/EXTINF+分片大小估节目码率 bps，随速度一同缓存供 pickBestLine 余量比；
 *  B3 网络指纹——换 Wi-Fi/蜂窝（activeNetwork handle 变化）即作废全部旧测速表，6h TTL 不再跨网复用。
 * 内存工作表 + 6h 磁盘回灌（H5，web fongmi_lspeed 等价）。0=失败、-1=采样失败但播放中（不判死）。
 */
object LineSpeed {
    const val CONCURRENCY = 4
    const val MAX_LINES = 10
    const val DISK_TTL = 6 * 3600_000L          // 6h 持久复用窗口（web fongmi_lspeed.t 判定同值）
    private const val BUDGET_MS = 2500L
    private const val MULTI_BUDGET_MS = 4000L   // 多切片总预算（用户 2026-11-05）
    private const val MULTI_SEGS = 5            // 采样分片数上限
    private const val MULTI_RANGE = "bytes=0-2097151" // 直连文件改拉 2MB（原 128KB 样本太小不准）
    // A2 连通快检预算=建连+响应头（不含 body）。模拟器实测 2026-11-05：800ms 按「收完清单」
    // 会把 VPN/海外源的 1-2s 握手全误判死（用户实机全线慢/不可用的根因），改头计时并放宽到 2000ms
    private const val PROBE_BUDGET_MS = 2000L
    private const val WARMUP_MS = 1200L         // A1 TCP 慢启动暖机窗
    private const val STEADY_MIN_MS = 300L      // A1 稳态窗口下限，不足回退含暖机原值
    private const val SEG_PARALLEL = 2          // B2 轮内并发分片数

    /** 一行的采样产物：kbps=中位数速率（0=判死）；bps=节目码率 bytes/s（A4 余量参照，未知 null） */
    class SpeedSample(val kbps: Long, val bps: Long?)

    /** 缓存记录：sp=各线 KB/s（null 未测、0 失败、-1 采样失败但播放中）；br=各线节目码率 bytes/s */
    data class SpeedRec(val sp: List<Long?>, val br: List<Long?>)

    private val cache = ConcurrentHashMap<String, SpeedRec>()
    // 半途表修复（发布走查 2026-11-05）：measure 中写的是含 null 的进行时快照，协程被取消则永驻；
    // 命中不完整表时仅当"该键正在测"才早退（在测不重测），否则作废重测，防止残缺表让剩余线路永不再测
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val disk = DiskBacked<SpeedRec>(
        MemCache<SpeedRec>(256), "ls:",
        { v -> JSONObject().put("sp", jsonOf(v.sp)).put("br", jsonOf(v.br)).toString() },
        { s -> runCatching {
            val o = JSONObject(s)
            SpeedRec(jsonList(o.optJSONArray("sp")), jsonList(o.optJSONArray("br")))
        }.getOrNull() },
    )
    private val M3U8_RE = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE)
    private val EXTINF_RE = Regex("#EXTINF:([0-9.]+)", RegexOption.IGNORE_CASE)
    private val BW_RE = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)

    fun key(sourceKey: String, vodId: String, ep: Int): String = "$sourceKey|$vodId|$ep"

    // ===== B3 网络指纹 =====
    @Volatile private var appCtx: Context? = null
    fun init(context: Context) { appCtx = context.applicationContext }

    private fun netFid(): String? {
        val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val n = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(n)
        val t = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "eth"
            else -> "net"
        }
        return "$t:${n.networkHandle}"
    }

    /** 网络变化（换 Wi-Fi/切蜂窝）即清空全部测速表：旧网的速度对当前链路无参考价值 */
    private fun guardNet() {
        val c = appCtx ?: return
        val fid = netFid() ?: return
        val p = c.getSharedPreferences("libretvaz_lspeed", Context.MODE_PRIVATE)
        val old = p.getString("fid", null)
        if (old == fid) return
        if (old != null) clearAll()
        p.edit().putString("fid", fid).apply()
    }

    /** 读缓存：内存优先，未中回灌 6h 内磁盘结果（web speedProbe 前 LINE_SPEED 命中语义 + paint 复用 pe.sp） */
    fun cached(key: String): List<Long?>? = cachedRec(key)?.sp

    /** A4：与 cached 同行的节目码率（bytes/s，null=未测/未知） */
    fun cachedBps(key: String): List<Long?>? = cachedRec(key)?.br

    private fun cachedRec(key: String): SpeedRec? {
        guardNet()
        cache[key]?.let { return it }
        val v = disk.get(key, DISK_TTL) ?: return null
        cache[key] = v
        return v
    }

    /** 写缓存：bps 省略时保留原记录码率（-1 重分类等只动 sp 的场合不丢 br） */
    fun put(key: String, speeds: List<Long?>, bps: List<Long?>? = null) {
        val rec = SpeedRec(speeds, bps ?: cache[key]?.br ?: List(speeds.size) { null })
        cache[key] = rec
        if (speeds.any { it != null }) disk.set(key, rec)
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
        // 完整表直接复用；不完整表只有"正在测"才复用（在测不重测），半途残表作废重测
        cachedRec(key)?.let { hit ->
            if (hit.sp.none { it == null } || key in inFlight) return@coroutineScope hit.sp
            cache.remove(key)
        }
        // minor#7：查-再挂两步合并为 set.add 原子注册——并发 measure 只剩一个赢家实测，落败方回进行时快照
        if (!inFlight.add(key)) return@coroutineScope cache[key]?.sp ?: List(minOf(lines.size, MAX_LINES)) { null }
        val n = minOf(lines.size, MAX_LINES)
        val sp = arrayOfNulls<Long>(n)
        val br = arrayOfNulls<Long>(n)
        cache[key] = SpeedRec(sp.toList(), br.toList())
        onLine(sp.toList())
        val sem = Semaphore(CONCURRENCY)
        try {
            val jobs = (0 until n).map { i ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val u = lines[i].list.getOrNull(ep) ?: lines[i].list.firstOrNull()
                        var s: SpeedSample? = null
                        if (u != null && u.link.isNotEmpty()) {
                            s = try { probeAndSample(u.link) } catch (e: Exception) { null }
                        }
                        var v = s?.kbps ?: 0L
                        // 当前线测 0 但播放器实际流畅→重分类为「测速失败」，显示层以播放为准
                        if (v == 0L && i == currentLine && playingHealthy()) v = -1L
                        sp[i] = v
                        br[i] = s?.bps
                        cache[key] = SpeedRec(sp.toList(), br.toList())
                        onLine(sp.toList())
                    }
                }
            }
            jobs.forEach { it.join() }
            val finalSp = sp.toList()
            if (finalSp.any { it != null }) disk.set(key, SpeedRec(finalSp, br.toList())) // 终表落盘 6h
            finalSp
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * 两阶段采样（A2+A1+A3+B2+A4，用户 2026-11-05 批准）：
     * m3u8：拉清单即连通快检（>800ms 判死），存活再分轮采分片；直连：建连+首字节快检后续读，同连接不重连。
     */
    fun probeAndSample(link: String): SpeedSample {
        if (!M3U8_RE.containsMatchIn(link)) return sampleDirect(link)
        // 探针只计「建连+重定向+响应头」；清单 body 读取不计快检预算
        val p0 = System.currentTimeMillis()
        val conn = openGet(link, null, 5000)
        val headMs = System.currentTimeMillis() - p0
        val text = try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { conn.disconnect() }
        if (headMs > PROBE_BUDGET_MS) return SpeedSample(0L, null)
        val (segs, durs) = segsWithDurations(text, link, MULTI_SEGS)
        if (segs.isEmpty()) throw IllegalStateException("清单无分片")
        return sampleRounds(segs, durs, BW_RE.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { it / 8 })
    }

    /**
     * 首字节快检档（用户 2026-11-06 批准，快搜自动标记用）：只测建连(+重定向)+响应头，
     * 直连再等首字节；不采吞吐、不落缓存。超 A2 预算(2s)或失败返回 null（判慢/不可达档）。
     */
    fun probeTtfb(link: String): Long? {
        val t0 = System.currentTimeMillis()
        val conn = try { openGet(link, null, PROBE_BUDGET_MS.toInt() + 1000) } catch (e: Exception) { return null }
        return try {
            if (!M3U8_RE.containsMatchIn(link)) conn.inputStream.read()
            val ms = System.currentTimeMillis() - t0
            if (ms <= PROBE_BUDGET_MS) ms else null
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }

    /** 线路地址→采样目标：m3u8 取清单首个分片（无分片视为失败，对齐 web「清单无分片」抛错），其余直接采样 */
    fun segOrSelf(link: String): String {
        if (!M3U8_RE.containsMatchIn(link)) return link
        val text = fetchText(link)
        val first = text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: throw IllegalStateException("清单无分片")
        return try { URL(URL(link), first).toExternalForm() } catch (e: Exception) { first }
    }

    /** 单地址采样吞吐（KB/s，web 原语义，直播测速等仍用）：掐表/异常前已收到字节按已收部分计速 */
    fun sampleKbps(u: String, budgetMs: Long = BUDGET_MS, range: String = "bytes=0-131071"): Long {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        val conn = openGet(u, range, 3000)
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

    /** 直连文件：openGet 已含握手，首字节超 A2 预算判死；否则续读到 2MB/预算，A1 剔除暖机后计速 */
    private fun sampleDirect(link: String): SpeedSample {
        val t0 = System.currentTimeMillis()
        val conn = openGet(link, MULTI_RANGE, 3000)
        var bytes = 0L
        var steady = 0L
        var tFirst = 0L
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(16384)
                val deadline = System.currentTimeMillis() + MULTI_BUDGET_MS
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    if (tFirst == 0L) tFirst = System.currentTimeMillis()
                    bytes += r
                    if (System.currentTimeMillis() - t0 >= WARMUP_MS) steady += r
                    if (System.currentTimeMillis() >= deadline) break
                }
            }
        } catch (e: Exception) {
            if (bytes == 0L) throw e
        } finally {
            conn.disconnect()
        }
        if (bytes == 0L) return SpeedSample(0L, null)
        val t1 = System.currentTimeMillis()
        val steadyMs = t1 - (t0 + WARMUP_MS)
        val steadyUsed = steady > 0L && steadyMs >= STEADY_MIN_MS
        val kb = if (steadyUsed) steady * 1000 / steadyMs / 1024
        else bytes * 1000 / maxOf(1L, t1 - t0) / 1024
        if (tFirst == 0L || tFirst - t0 > PROBE_BUDGET_MS) return SpeedSample(0L, null)
        return SpeedSample(kb, null)
    }

    private class SegStat(val bytes: Long, val steady: Long, val t0: Long, val tEnd: Long, val eof: Boolean)

    /**
     * m3u8 分轮采样（B2 轮内 2 并发、轮间串行）：每轮=Σ稳态字节/Σ并行稳态窗口得轮速率；
     * 全轮中位数为终值（A3）；eof 分片大小÷EXTINF 时长均值估节目码率（A4，master BANDWIDTH 优先）。
     */
    private fun sampleRounds(segs: List<String>, durs: List<Double>, knownBps: Long?): SpeedSample {
        val deadline = System.currentTimeMillis() + MULTI_BUDGET_MS
        val rates = mutableListOf<Long>()
        var sizeSum = 0L
        var durSum = 0.0
        var nEst = 0
        var i = 0
        while (i < segs.size && System.currentTimeMillis() < deadline) {
            val idx = (i until minOf(i + SEG_PARALLEL, segs.size)).toList()
            i += idx.size
            // minor#7：join 带超时返回后工作线程可能仍在跑，普通数组无 happens-before——
            // 跨线程交付改 AtomicReferenceArray 安全发布；采样计时语义（t0/deadline/预算）原样保持
            val stats = AtomicReferenceArray<SegStat>(idx.size)
            val ts = idx.mapIndexed { j, si ->
                Thread { stats.set(j, fetchSegStat(segs[si], deadline)) }.also { it.start() }
            }
            for (t in ts) {
                val rem = deadline - System.currentTimeMillis()
                t.join(if (rem > 0) rem else 1)
            }
            val ok = (0 until idx.size).mapNotNull { stats.get(it) }
            if (ok.isEmpty()) break
            for (j in idx.indices) {
                val st = stats.get(j) ?: continue
                // 码率样本只算读完整、够大且非嵌套清单的分片（master 首"分片"可能是子 m3u8 文本）
                if (st.eof && st.bytes >= 4096 && !M3U8_RE.containsMatchIn(segs[idx[j]])) {
                    val d = durs.getOrNull(idx[j]) ?: 0.0
                    if (d > 0.0) { sizeSum += st.bytes; durSum += d; nEst++ }
                }
            }
            val bytes = ok.sumOf { it.bytes }
            if (bytes == 0L) continue
            val steady = ok.sumOf { it.steady }
            val wall = maxOf(1L, ok.maxOf { it.tEnd } - ok.minOf { it.t0 })
            val steadyWall = wall - WARMUP_MS
            val steadyUsed = steady > 0L && steadyWall >= STEADY_MIN_MS
            val rate = if (steadyUsed) steady * 1000 / steadyWall / 1024
            else bytes * 1000 / wall / 1024
            rates.add(rate)
        }
        if (rates.isEmpty()) throw IllegalStateException("分片采样失败")
        rates.sort()
        val mid = if (rates.size % 2 == 1) rates[rates.size / 2]
        else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
        val estBps = if (knownBps != null && knownBps > 0) knownBps
        else if (nEst >= 2) runCatching { ((sizeSum / nEst) / maxOf(1.0, durSum / nEst)).toLong() }.getOrNull()
        else null
        return SpeedSample(mid, estBps?.takeIf { it > 0 })
    }

    /** 单分片采样：只计连接建立后收到字节；暖机窗外的进 steady；失败且零字节返回 null */
    private fun fetchSegStat(u: String, deadline: Long): SegStat? {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        var steady = 0L
        var eof = false
        val conn = try { openGet(u, MULTI_RANGE, 3000) } catch (e: Exception) { return null }
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) { eof = true; break }
                    bytes += r
                    if (System.currentTimeMillis() - t0 >= WARMUP_MS) steady += r
                    if (System.currentTimeMillis() >= deadline) break
                }
            }
        } catch (e: Exception) {
            if (bytes == 0L) return null
        } finally {
            conn.disconnect()
        }
        return SegStat(bytes, steady, t0, System.currentTimeMillis(), eof)
    }

    /** m3u8 清单前 n 个分片绝对 URL + 对齐的 EXTINF 秒数（缺省 0），A4 码率样本用 */
    private fun segsWithDurations(text: String, base: String, n: Int): Pair<List<String>, List<Double>> {
        val urls = mutableListOf<String>()
        val durs = mutableListOf<Double>()
        var pending = 0.0
        for (raw in text.split("\n")) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("#")) {
                EXTINF_RE.find(s)?.groupValues?.get(1)?.toDoubleOrNull()?.let { pending = it }
                continue
            }
            urls.add(try { URL(URL(base), s).toExternalForm() } catch (e: Exception) { s })
            durs.add(pending)
            pending = 0.0
            if (urls.size >= n) break
        }
        return urls to durs
    }

    /** m3u8 清单前 n 个分片绝对 URL（跳过注释行，对齐 web 首个分片语义的扩展） */
    fun segUrls(link: String, n: Int): List<String> {
        val text = fetchText(link)
        return text.split("\n").map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(n)
            .map { try { URL(URL(link), it).toExternalForm() } catch (e: Exception) { it } }
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

    private fun jsonOf(l: List<Long?>): JSONArray = JSONArray().apply {
        l.forEach { if (it == null) put(JSONObject.NULL) else put(it) }
    }

    private fun jsonList(a: JSONArray?): List<Long?> =
        if (a == null) emptyList() else (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) }
}
