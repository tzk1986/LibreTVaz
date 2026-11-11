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
    fun get(context: Context): VocabStore {
        // P0-1（审计 2026-11-11）：PinIndex 构造前确保拼音首字母查表已装（读败=降级无联想，同原 coll 缺失）
        com.tzk1986.libretvaz.data.perf.PinTable.ensureLoaded(context)
        return inst ?: VocabStore(context.applicationContext).also { inst = it }
    }
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

    // 用户 2026-11-06 走查 #8：防抖回调原在主线程同步编码 8000 词 JSONArray/JSONObject=掉帧源
    // → 主线程只取一致性快照（PinIndex 持锁瞬时），编码+写盘移后台单线程
    // 声明必须先于下方 init 种子块：属性与 init 按声明序执行，置后则 init 直调 persist 时 NPE（2026-11-10 装机崩溃实锤）
    private val persistExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vocab-persist").apply { isDaemon = true }
    }

    init {
        // TVBox 对标 S1（2026-11-10）：首启一次性灌 40 条种子热榜进点播池（热搜冷启动不空白）；
        // 闸门存词库自身 prefs，同版本幂等；用户清词后不回填（与内置源种闸门语义一致）
        if (SeedHot.shouldSeed(prefs.getString(SeedHot.PREF_VER, "").orEmpty(), SeedHot.VER)) {
            addWords(SeedHot.LIST, zone = false, w = SeedHot.BUMP)
            persist(false) // 立即落盘：防 500ms 防抖窗内进程被杀致「闸门已写、词未存」丢种子
            prefs.edit().putString(SeedHot.PREF_VER, SeedHot.VER).apply()
        }
    }

    fun index(zone: Boolean): PinIndex = if (zone) idxZone else idxVod

    /** 片名入本池词库（默认 +1 权重，含核心词派生），等价 web addWords；种子灌入传高权重 */
    fun addWords(names: List<String>, zone: Boolean, w: Long = 1) {
        if (names.isEmpty()) return
        val pi = index(zone)
        for (n in names) {
            if (n.isBlank()) continue
            pi.add(n); pi.bump(n, w)
            val c = PerfCore.coreName(n)
            if (c.isNotEmpty() && c != n) { pi.add(c); pi.bump(c, w) }
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

    /** A8（审计 2026-11-11）：搜索历史单条删除（其余词保序） */
    fun dropKw(kw: String, zone: Boolean) {
        prefs.edit().putString(kwKey(zone), JSONArray(kws(zone).filter { it != kw }).toString()).apply()
    }

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
        val (ws, wfs) = index(zone).snapshot()
        persistExec.execute {
            runCatching {
                val w = JSONObject()
                wfs.forEach { (k, v) -> w.put(k, v) }
                prefs.edit()
                    .putString(wordsKey(zone), JSONArray(ws).toString())
                    .putString(weightsKey(zone), w.toString())
                    .apply()
            }
        }
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
