package com.tzk1986.libretvaz.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.data.perf.PinIndex
import org.json.JSONArray
import org.json.JSONObject

/** 双池词库进程单例（web VocabPools 等价）：点播/专区各自独立，跨页面共享同一实例 */
object VocabPools {
    private var inst: VocabStore? = null

    @Synchronized
    fun get(context: Context): VocabStore =
        inst ?: VocabStore(context.applicationContext).also { inst = it }
}

/**
 * 词库双池 + 搜索历史双池（web createVocabPool/pushKw 等价）：
 * 词条与词频按池持久化（vod/zone 键隔离互不可见）；入库同步派生核心词；500ms 防抖回写；
 * 种子节流时间戳与搜索历史同库存放。
 */
class VocabStore(context: Context) {
    private val prefs = context.getSharedPreferences("libretvaz_vocab", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val idxVod = restore(false)
    private val idxZone = restore(true)
    private var pendingVod: Runnable? = null
    private var pendingZone: Runnable? = null

    fun index(zone: Boolean): PinIndex = if (zone) idxZone else idxVod

    /** 片名入本池词库（+1 权重，含核心词派生），等价 web addWords */
    fun addWords(names: List<String>, zone: Boolean) {
        if (names.isEmpty()) return
        val pi = index(zone)
        for (n in names) {
            if (n.isBlank()) continue
            pi.add(n); pi.bump(n, 1)
            val c = PerfCore.coreName(n)
            if (c.isNotEmpty() && c != n) { pi.add(c); pi.bump(c, 1) }
        }
        persistSoon(zone)
    }

    /** 权重加码（进详情 +2 等，web vocab().bump 等价） */
    fun bump(name: String?, k: Long, zone: Boolean) {
        if (name.isNullOrEmpty()) return
        index(zone).bump(name, k)
        persistSoon(zone)
    }

    // ---- 搜索历史（10 条上限，队头最新） ----

    fun kws(zone: Boolean): List<String> = runCatching {
        val a = JSONArray(prefs.getString(kwKey(zone), "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun pushKw(kw: String, zone: Boolean) {
        val h = (listOf(kw) + kws(zone).filter { it != kw }).take(10)
        prefs.edit().putString(kwKey(zone), JSONArray(h).toString()).apply()
    }

    fun clearKw(zone: Boolean) { prefs.edit().remove(kwKey(zone)).apply() }

    // ---- 词库种子节流 ----

    fun seedAt(zone: Boolean): Long = prefs.getLong(seedKey(zone), 0L)

    fun setSeedAt(t: Long, zone: Boolean) { prefs.edit().putLong(seedKey(zone), t).apply() }

    // ---- 持久化 ----

    private fun restore(zone: Boolean): PinIndex = runCatching {
        val pi = PinIndex(max = MAX_WORDS, weights = weightsOf(zone))
        val words = wordsOf(zone)
        pi.addMany(words)
        for (w in words) {
            val c = PerfCore.coreName(w)
            if (c.isNotEmpty() && c != w) pi.add(c)
        }
        pi
    }.getOrDefault(PinIndex(max = MAX_WORDS))

    private fun persistSoon(zone: Boolean) {
        val r = Runnable { persist(zone) }
        if (zone) { pendingZone?.let { handler.removeCallbacks(it) }; pendingZone = r }
        else { pendingVod?.let { handler.removeCallbacks(it) }; pendingVod = r }
        handler.postDelayed(r, PERSIST_DEBOUNCE_MS)
    }

    private fun persist(zone: Boolean) {
        val pi = index(zone)
        val w = JSONObject()
        pi.weightsMap().forEach { (k, v) -> w.put(k, v) }
        prefs.edit()
            .putString(wordsKey(zone), JSONArray(pi.list()).toString())
            .putString(weightsKey(zone), w.toString())
            .apply()
    }

    private fun wordsOf(zone: Boolean): List<String> = runCatching {
        val a = JSONArray(prefs.getString(wordsKey(zone), "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun weightsOf(zone: Boolean): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString(weightsKey(zone), "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    private fun wordsKey(zone: Boolean) = if (zone) "words_zone" else "words_vod"
    private fun weightsKey(zone: Boolean) = if (zone) "wfreq_zone" else "wfreq_vod"
    private fun kwKey(zone: Boolean) = if (zone) "kw_zone" else "kw_vod"
    private fun seedKey(zone: Boolean) = if (zone) "seed_at_zone" else "seed_at_vod"

    companion object {
        private const val MAX_WORDS = 8000
        private const val PERSIST_DEBOUNCE_MS = 500L
    }
}
