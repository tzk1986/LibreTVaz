package com.tzk1986.libretvaz.data

import org.json.JSONObject

/**
 * TVBox 对标 C13（2026-11-10）：配置备份/还原纯函数（对齐 web「数据备份」JSON 导出导入）。
 * 包格式 {"v":1,"ts":…,"app":"LibreTVaz","prefs":{库名:{键:{"t":类型,"v":值}}}}，
 * 类型标签 s/b/i/l/f；null 与不支持类型不写入包；未知类型 unpack 时跳过并计数。
 */
object Backup {

    const val APP = "LibreTVaz"
    const val VER = 1

    /** 一条还原项：t=类型标签（s/b/i/l/f），v=已按类型还原的值 */
    data class Entry(val t: Char, val v: Any)

    /** SharedPreferences.all 快照（库名→键→值）打包为 JSON 文本 */
    fun pack(snapshot: Map<String, Map<String, Any?>>, ts: Long = System.currentTimeMillis()): String {
        val prefs = JSONObject()
        for ((lib, keys) in snapshot) {
            val libObj = JSONObject()
            for ((k, v) in keys) {
                val t = tagOf(v) ?: continue // null/不支持类型不打包
                libObj.put(k, JSONObject().put("t", t.toString()).put("v", v))
            }
            prefs.put(lib, libObj)
        }
        return JSONObject()
            .put("v", VER)
            .put("ts", ts)
            .put("app", APP)
            .put("prefs", prefs)
            .toString()
    }

    /** 解析备份文本：坏 JSON/版本不符/缺 prefs 段返回 null（不抛）；否则 (库→键→Entry, 跳过数) */
    fun unpack(json: String): Pair<Map<String, Map<String, Entry>>, Int>? = try {
        val root = JSONObject(json)
        if (root.optInt("v", -1) != VER) null
        else root.optJSONObject("prefs")?.let { p ->
            val out = HashMap<String, Map<String, Entry>>()
            var skipped = 0
            for (lib in p.keys()) {
                val libObj = p.optJSONObject(lib) ?: continue
                val entries = HashMap<String, Entry>()
                for (k in libObj.keys()) {
                    val e = libObj.optJSONObject(k)
                    val t = e?.optString("t")?.singleOrNull()
                    val v = e?.opt("v")
                    if (v == null || v == JSONObject.NULL) { skipped++; continue }
                    val entry = when (t) {
                        's' -> Entry(t, e.getString("v"))
                        'b' -> Entry(t, e.getBoolean("v"))
                        'i' -> Entry(t, e.getInt("v"))
                        'l' -> Entry(t, e.getLong("v"))
                        'f' -> Entry(t, e.getDouble("v").toFloat())
                        else -> null
                    }
                    if (entry == null) skipped++ else entries[k] = entry
                }
                out[lib] = entries
            }
            out to skipped
        }
    } catch (_: Exception) {
        null
    }

    /** 值→类型标签；null 或不在 s/b/i/l/f 五档内返回 null（不打包） */
    private fun tagOf(v: Any?): Char? = when (v) {
        is String -> 's'
        is Boolean -> 'b'
        is Int -> 'i'
        is Long -> 'l'
        is Float -> 'f'
        else -> null
    }
}
