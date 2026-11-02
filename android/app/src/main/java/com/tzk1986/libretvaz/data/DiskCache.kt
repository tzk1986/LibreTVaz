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

    /** 周期/冷启动过期表：各命名空间的价值窗口（aggs 过期分片仍秒开首绘，给足 24h） */
    val GC_TTL = listOf(
        "types:" to 24 * 3600_000L,
        "aggs:" to 24 * 3600_000L,
        "detail:" to 24 * 3600_000L,
        "zsp:" to 7 * 24 * 3600_000L,
        "search:" to 2 * 3600_000L, // 搜索分片仅内存，保留前缀语义防手工落盘漏清
    )

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

    /** 按 lastModified 过命名空间 TTL 删除（冷启动兜底 + WorkManager 6h 周期任务共用） */
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val table = GC_TTL.map { sanitize(it.first) to it.second }
        files().forEach { f ->
            val ttl = table.firstOrNull { f.name.startsWith(it.first) }?.second ?: return@forEach
            if (now - f.lastModified() > ttl) runCatching { f.delete() }
        }
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
        v.forEach { put(JSONObject().put("i", it.id).put("n", it.name).put("p", it.pid)) }
    }.toString()

    fun decTypes(s: String): List<VodType>? = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            VodType(o.getString("i"), o.getString("n"), o.optString("p"))
        }
    }.getOrNull()

    fun encDetail(v: VideoDetail): String = JSONObject()
        .put("t", v.title).put("c", v.cover).put("d", v.desc)
        .put("tp", v.typeName).put("yr", v.year).put("ar", v.area)
        .put("ac", v.actor).put("di", v.director).put("sn", v.sourceName)
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
            nullStr(o, "yr"), nullStr(o, "ar"), nullStr(o, "ac"), nullStr(o, "di"),
            o.getString("sn"), lines,
        )
    }.getOrNull()

    fun encLong(v: Long): String = v.toString()

    fun decLong(s: String): Long? = s.trim().toLongOrNull()
}
