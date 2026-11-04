package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.MemCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 磁盘持久层（B11，web localStorage 分片等价）：CMS 数据分片以 JSON 文件存 cacheDir/cms/，
 * 条目头记写入毫秒 t；64MB 上限按 lastModified LRU 逐出，读命中 touch 续命。
 * 文件名 = 键前 48 字符消毒（非字母数字转下划线）+ "_" + SHA1(key) 短哈希，
 * 使 removePrefix 能按命名空间前缀整体删除（如 "aggs:" 消毒为 "aggs_" 前缀）。
 * 未 init（单测/冷启前）所有读写静默 no-op，不影响内存缓存可用性。
 */
object DiskCache {
    const val MAX_BYTES = 64L shl 20          // CMS 分片盘上限
    const val LOW_WATER_BYTES = 32L shl 20    // 退出修剪低水位

    /** 过期硬清阈值（对齐 web pruneExpiredCache：所有分片按写入 t 统一 7 天，与 SWR 读写窗口无关） */
    const val HARD_TTL_MS = 7 * 24 * 3600_000L

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "cms-disk").apply { isDaemon = true } }
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        val d = File(context.cacheDir, "cms")
        if (!d.exists()) d.mkdirs()
        dir = d
        pool.execute {
            purgeExpired()
            trimTo(MAX_BYTES)
        }
    }

    /** 键/前缀消毒：截 48 字符、非字母数字转下划线（命名空间前缀消毒结果与键消毒结果天然同头） */
    fun sanitize(s: String): String = buildString {
        for (c in s.take(48)) append(if (c.isLetterOrDigit()) c else '_')
    }

    fun fileName(key: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(12)
        return sanitize(key) + "_" + h + ".json"
    }

    private fun files(): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    /** 条目文件 {"t":写入毫秒,"d":负载JSON}；tmp+rename 原子替换；盘失败静默（内存层不受影响） */
    fun write(key: String, t: Long, payload: String) {
        val d = dir ?: return
        val name = fileName(key)
        pool.execute {
            runCatching {
                val o = JSONObject().put("t", t).put("d", payload)
                val tmp = File(d, "$name.tmp")
                tmp.writeText(o.toString())
                val f = File(d, name)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    fun read(key: String): Pair<Long, String>? {
        val d = dir ?: return null
        val f = File(d, fileName(key))
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            o.getLong("t") to o.getString("d")
        }.getOrElse { runCatching { f.delete() }; null } // 半截坏文件即弃
    }

    fun touch(key: String) {
        val d = dir ?: return
        runCatching { File(d, fileName(key)).setLastModified(System.currentTimeMillis()) }
    }

    /**
     * 过期硬清（web pruneExpiredCache 等价）：按条目头写入时间 t 统一 7 天删除，
     * 读命中 touch 不续命（t 记写入时刻，与 web 语义一致；LRU 逐出才看 lastModified）。
     */
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val cut = now - HARD_TTL_MS
        files().forEach { f ->
            val t = runCatching { JSONObject(f.readText()).getLong("t") }.getOrDefault(0L)
            if (t < cut) runCatching { f.delete() }
        }
    }

    /** 即时删+队尾再删一次：防「未落盘的挂起 write」在删除后又把文件写回来 */
    fun remove(key: String) {
        val d = dir ?: return
        runCatching { File(d, fileName(key)).delete() }
        pool.execute { runCatching { File(d, fileName(key)).delete() } }
    }

    fun removePrefix(ns: String): Int {
        val p = sanitize(ns)
        val hit = files().filter { it.name.startsWith(p) }
        hit.forEach { runCatching { it.delete() } }
        return hit.size
    }

    fun clearAll() { files().forEach { runCatching { it.delete() } } }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** 按 lastModified 升序逐出到 maxBytes 以下（退出修剪/周期任务/超限自愈） */
    fun trimTo(maxBytes: Long) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (f in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (runCatching { f.delete() }.isSuccess) total -= len
        }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}

/**
 * 内存+磁盘双层缓存包装（B11）：API 与 MemCache 同名（get/getRaw/set/clear），
 * 读先内存、未中回灌磁盘（保留原 t，新鲜度不因回灌失真）并 touch 续 LRU；写双写（盘异步）；清双清。
 */
class DiskBacked<V>(
    private val mem: MemCache<V>,
    private val ns: String,
    private val enc: (V) -> String,
    private val dec: (String) -> V?,
) {
    fun get(key: String, ttl: Long, now: Long = System.currentTimeMillis()): V? {
        mem.get(key, ttl, now)?.let { return it }
        val e = restore(key) ?: return null
        return if (now - e.t <= ttl) e.v else null
    }

    fun getRaw(key: String): MemCache.Entry<V>? = mem.getRaw(key) ?: restore(key)

    private fun restore(key: String): MemCache.Entry<V>? {
        val (t, p) = DiskCache.read(key) ?: return null
        val v = dec(p) ?: return null
        mem.set(key, v, t)
        DiskCache.touch(key)
        return MemCache.Entry(t, v)
    }

    fun set(key: String, value: V, now: Long = System.currentTimeMillis()) {
        mem.set(key, value, now)
        val s = runCatching { enc(value) }.getOrNull() ?: return
        DiskCache.write(key, now, s)
    }

    fun remove(key: String) { mem.remove(key); DiskCache.remove(key) }

    fun removePrefix(prefix: String) { mem.removePrefix(prefix); DiskCache.removePrefix(prefix) }

    fun clear() { mem.clear(); DiskCache.removePrefix(ns) }
}

/** 磁盘 JSON 编解码（B11）：字段短名省体积；坏数据 dec 返回 null 视作未命中 */
object CmsCodecs {
    private fun nullStr(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k)

    fun encSummaries(v: List<VodSummary>): String = JSONArray().apply {
        v.forEach { put(
            JSONObject()
                .put("sk", it.sourceKey).put("sn", it.sourceName).put("su", it.sourceUrl)
                .put("id", it.vodId).put("n", it.name).put("pic", it.pic)
                .put("tp", it.typeName).put("yr", it.year).put("ar", it.area)
                .put("rm", it.remarks).put("ad", it.isAdult),
        ) }
    }.toString()

    fun decSummaries(s: String): List<VodSummary>? = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            VodSummary(
                o.getString("sk"), o.getString("sn"), o.getString("su"),
                o.getString("id"), o.getString("n"),
                nullStr(o, "pic"), nullStr(o, "tp"), nullStr(o, "yr"),
                nullStr(o, "ar"), nullStr(o, "rm"), o.optBoolean("ad"),
            )
        }
    }.getOrNull()

    fun encTypes(v: List<VodType>): String = JSONArray().apply {
        v.forEach { put(JSONObject().put("i", it.id).put("n", it.name).put("p", it.pid)
            .put("e", JSONObject(it.extend as Map<*, *>))
            // 旧分片无 "e"，decTypes 按空 map 兼容
        ) }
    }.toString()

    fun decTypes(s: String): List<VodType>? = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ext = CmsParser.parseExtend(o.opt("e"))
            VodType(o.getString("i"), o.getString("n"), o.optString("p"), ext)
        }
    }.getOrNull()

    fun encDetail(v: VideoDetail): String = JSONObject()
        .put("t", v.title).put("c", v.cover).put("d", v.desc)
        .put("tp", v.typeName).put("yr", v.year).put("ar", v.area)
        .put("ac", v.actor).put("di", v.director).put("rm", v.remarks).put("sn", v.sourceName)
        .put("ln", JSONArray().apply {
            v.lines.forEach { l -> put(JSONObject().put("f", l.flag).put("e", JSONArray().apply {
                l.list.forEach { e -> put(JSONObject().put("n", e.name).put("l", e.link)) }
            })) }
        })
        .toString()

    fun decDetail(s: String): VideoDetail? = runCatching {
        val o = JSONObject(s)
        val la = o.getJSONArray("ln")
        val lines = (0 until la.length()).map { li ->
            val l = la.getJSONObject(li)
            val ea = l.getJSONArray("e")
            PlayLine(
                l.getString("f"),
                (0 until ea.length()).map { ei ->
                    val e = ea.getJSONObject(ei)
                    PlayEpisode(e.getString("n"), e.getString("l"))
                },
            )
        }
        VideoDetail(
            o.getString("t"), nullStr(o, "c"), nullStr(o, "d"), nullStr(o, "tp"),
            nullStr(o, "yr"), nullStr(o, "ar"), nullStr(o, "ac"), nullStr(o, "di"), nullStr(o, "rm"),
            o.getString("sn"), lines,
        )
    }.getOrNull()

    fun encLong(v: Long): String = v.toString()

    fun decLong(s: String): Long? = s.trim().toLongOrNull()
}
