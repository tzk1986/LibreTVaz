package com.tzk1986.libretvaz.data

import android.content.Context
import org.json.JSONObject

/** 一次续播位（web fongmi_prog 条目等价）：line 记序数、flag 记名（线路表顺序变化时按名还原） */
data class Prog(val line: Int, val flag: String, val ep: Int, val sec: Long, val dur: Long, val ts: Long)

/**
 * 跨会话播放进度（web fongmi_prog 等价）：键「vodId|sourceKey」，单 JSON blob 落 SharedPreferences。
 * 写入时机=播放中 5s 节流 + 暂停即写 + 离页写；本集播完清除；写时顺带清理超 30 天或已脱离历史+收藏的孤儿项。
 */
class ProgStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun all(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }

    fun get(vodId: String, sourceKey: String): Prog? =
        runCatching {
            all().getJSONObject(key(vodId, sourceKey)).let { o ->
                Prog(o.optInt("line"), o.optString("flag"), o.optInt("ep"), o.optLong("sec"), o.optLong("dur"), o.optLong("ts"))
            }
        }.getOrNull()

    fun save(vodId: String, sourceKey: String, p: Prog, alive: Set<String>) = synchronized(LOCK) {
        val o = all()
        o.put(key(vodId, sourceKey), JSONObject().put("line", p.line).put("flag", p.flag).put("ep", p.ep).put("sec", p.sec).put("dur", p.dur).put("ts", p.ts))
        val now = p.ts
        val dead = o.keys().asSequence().filter { k ->
            val e = o.optJSONObject(k)
            e == null || !alive.contains(k) || now - e.optLong("ts") > ORPHAN_MS
        }.toList()
        dead.forEach(o::remove)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    fun clear(vodId: String, sourceKey: String) = removeKeys(setOf(key(vodId, sourceKey)))

    /** 删除/清空历史、收藏后调用：两池均脱离或超 30 天的 prog 一并清掉（web alive 语义等价） */
    fun pruneOrphans(context: Context) = synchronized(LOCK) {
        val alive = aliveKeys(context)
        val now = System.currentTimeMillis()
        val o = all()
        val dead = o.keys().asSequence().filter { k ->
            val e = o.optJSONObject(k)
            e == null || !alive.contains(k) || now - e.optLong("ts") > ORPHAN_MS
        }.toList()
        if (dead.isNotEmpty()) {
            dead.forEach(o::remove)
            prefs.edit().putString(KEY, o.toString()).apply()
        }
    }

    /** 删除/清空历史、收藏连带清续播位（web prog 孤儿清理的显式版） */
    fun removeKeys(keys: Set<String>) = synchronized(LOCK) {
        val o = all()
        var dirty = false
        keys.forEach { if (o.has(it)) { o.remove(it); dirty = true } }
        if (dirty) prefs.edit().putString(KEY, o.toString()).apply()
    }

    companion object {
        // D38（审计 2026-11-11）：prog 为单 blob 读-改-写，KeepStore 各处 new ProgStore 做 pruneOrphans
        // 与详情页 save 可并发（且 save 已挪 IO 线程）→ 进程级锁串行化全部写路径，防丢更新
        private val LOCK = Any()

        private const val PREF = "libretvaz_prog"
        private const val KEY = "prog"
        private const val ORPHAN_MS = 30L * 24 * 3600_000

        fun key(vodId: String, sourceKey: String): String = "$vodId|$sourceKey"

        /** 存活集=历史+收藏两池（web progSave 的 alive 集等价） */
        fun aliveKeys(context: Context): Set<String> {
            val hist = HistStore(context)
            val keep = KeepStore(context)
            return (hist.list(false) + hist.list(true) + keep.list(false) + keep.list(true))
                .map { key(it.vod.vodId, it.vod.sourceKey) }.toSet()
        }
    }
}
