package com.tzk1986.libretvaz.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏/历史（对齐 web fongmi_keep/fongmi_hist）：单键数组，条目以 isAdult 分池
 * （对应 web a:1 标记与 isZoneRec），两池各按上限独立截断，新条目在头部。
 */
/** eps/last=更新检测基线（集数+更新备注），updated=已更新徽标；仅收藏池使用，历史池恒默认值 */
data class SavedItem(
    val vod: VodSummary,
    val ts: Long,
    val eps: Int? = null,
    val last: String? = null,
    val updated: Boolean = false,
)

/** checkKeeps 单条结果：当前集数基线 + 更新备注 + 是否有新内容 */
data class KeepChkResult(val eps: Int, val last: String, val updated: Boolean)

private fun saveToJson(arr: JSONArray, x: SavedItem) {
    val v = x.vod
    arr.put(JSONObject().apply {
        put("ts", x.ts)
        x.eps?.let { put("eps", it) }
        x.last?.let { put("last", it) }
        if (x.updated) put("upd", true)
        put("sourceKey", v.sourceKey); put("sourceName", v.sourceName); put("sourceUrl", v.sourceUrl)
        put("vodId", v.vodId); put("name", v.name)
        v.pic?.let { put("pic", it) }
        v.typeName?.let { put("typeName", it) }
        v.year?.let { put("year", it) }
        v.area?.let { put("area", it) }
        v.remarks?.let { put("remarks", it) }
        if (v.isAdult) put("isAdult", true)
    })
}

private fun saveFromJson(o: JSONObject): SavedItem? {
    val url = o.optString("sourceUrl")
    val id = o.optString("vodId")
    val name = o.optString("name")
    if (url.isEmpty() || id.isEmpty() || name.isEmpty()) return null
    return SavedItem(
        ts = o.optLong("ts"),
        eps = if (o.isNull("eps")) null else o.optInt("eps"),
        last = o.optString("last").ifBlank { null },
        updated = o.optBoolean("upd"),
        vod = VodSummary(
            sourceKey = o.optString("sourceKey", url),
            sourceName = o.optString("sourceName").ifBlank { url.substringAfter("://").substringBefore('/') },
            sourceUrl = url,
            vodId = id,
            name = name,
            pic = o.optString("pic").ifBlank { null },
            typeName = o.optString("typeName").ifBlank { null },
            year = o.optString("year").ifBlank { null },
            area = o.optString("area").ifBlank { null },
            remarks = o.optString("remarks").ifBlank { null },
            isAdult = o.optBoolean("isAdult"),
        ),
    )
}

/** 同一作品判定：源地址+vodId（web 同键 id|si；APK 源可变，存 url 更稳） */
private fun sameVod(a: SavedItem, b: SavedItem): Boolean =
    a.vod.sourceUrl == b.vod.sourceUrl && a.vod.vodId == b.vod.vodId

class KeepStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("libretvaz_keep", Context.MODE_PRIVATE)

    fun list(zone: Boolean): List<SavedItem> = all().filter { it.vod.isAdult == zone }

    fun contains(vod: VodSummary): Boolean = all().any { sameVod(it, SavedItem(vod, 0)) }

    /** 切换收藏；返回切换后是否收藏。上限 50/池（对齐 web 两池各 50） */
    fun toggle(vod: VodSummary): Boolean {
        val cur = SavedItem(vod, System.currentTimeMillis())
        val list = all().toMutableList()
        val at = list.indexOfFirst { sameVod(it, cur) }
        val added = at < 0
        if (added) list.add(0, cur) else list.removeAt(at)
        persist(truncate(list))
        if (!added) ProgStore(ctx).pruneOrphans(ctx)
        return added
    }

    fun remove(vod: VodSummary) {
        val cur = SavedItem(vod, 0)
        persist(all().filterNot { sameVod(it, cur) })
        ProgStore(ctx).pruneOrphans(ctx)
    }

    /** 清空本池收藏（用户 2026-11-05：收藏墙照历史墙同款，含「清空」） */
    fun clear(zone: Boolean) {
        persist(all().filterNot { it.vod.isAdult == zone })
        ProgStore(ctx).pruneOrphans(ctx)
    }

    fun updatedCount(zone: Boolean): Int = list(zone).count { it.updated }

    /** checkKeeps 回写（web keepchk 等价）：键=sourceUrl|vodId；基线随检更新，徽标只增不减（进详情才清） */
    fun applyChk(chks: Map<String, KeepChkResult>): Boolean {
        if (chks.isEmpty()) return false
        var changed = false
        val next = all().map { x ->
            val r = chks[x.vod.sourceUrl + "|" + x.vod.vodId] ?: return@map x
            val upd = x.updated || r.updated
            if (x.eps == r.eps && x.last == r.last && x.updated == upd) x
            else { changed = true; x.copy(eps = r.eps, last = r.last.ifBlank { null }, updated = upd) }
        }
        if (changed) persist(next)
        return changed
    }

    /** 进详情清「已更新」徽标（web renderDetail 抹 updated 等价） */
    fun clearUpdated(vod: VodSummary) {
        val cur = SavedItem(vod, 0)
        val list = all()
        val at = list.indexOfFirst { sameVod(it, cur) }
        if (at < 0 || !list[at].updated) return
        val next = list.toMutableList()
        next[at] = list[at].copy(updated = false)
        persist(next)
    }

    private fun truncate(list: List<SavedItem>): List<SavedItem> {
        var n = 0; var z = 0
        return list.filter { if (it.vod.isAdult) ++z <= 50 else ++n <= 50 }
    }

    private fun all(): List<SavedItem> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::saveFromJson) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<SavedItem>) {
        val arr = JSONArray()
        list.forEach { saveToJson(arr, it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object { private const val KEY = "keep" }
}

class HistStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("libretvaz_hist", Context.MODE_PRIVATE)

    fun list(zone: Boolean): List<SavedItem> = all().filter { it.vod.isAdult == zone }

    /** 进播放即写历史：按源+id 去重置顶；mergeName 开时同名跨源也只留最新（web 实验室「历史合并」） */
    fun push(vod: VodSummary, mergeName: Boolean = false, maxPerPool: Int = 60) {
        val cur = SavedItem(vod, System.currentTimeMillis())
        val list = all().filterNot {
            sameVod(it, cur) || (mergeName && it.vod.name == vod.name)
        }.toMutableList()
        list.add(0, cur)
        var n = 0; var z = 0
        val kept = list.filter { if (it.vod.isAdult) ++z <= maxPerPool else ++n <= maxPerPool }
        persist(kept)
    }

    fun remove(vod: VodSummary) {
        val cur = SavedItem(vod, 0)
        persist(all().filterNot { sameVod(it, cur) })
        ProgStore(ctx).pruneOrphans(ctx)
    }

    fun clear(zone: Boolean) {
        persist(all().filterNot { it.vod.isAdult == zone })
        ProgStore(ctx).pruneOrphans(ctx)
    }

    private fun all(): List<SavedItem> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::saveFromJson) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<SavedItem>) {
        val arr = JSONArray()
        list.forEach { saveToJson(arr, it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object { private const val KEY = "hist" }
}
