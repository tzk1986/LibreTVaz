package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import org.json.JSONArray
import org.json.JSONObject

/** 直播频道收藏条目（web fongmi_live_fav 元素：{name,url,group,srcIdx}） */
data class LiveFav(val name: String, val url: String, val group: String?, val srcIdx: Int)

/**
 * 直播收藏/上次频道/当前直播源下标持久化。
 * 对齐 web：fav 按 url+srcIdx 判重、置顶入列、上限 100；last 记 {srcIdx,url}；
 * srcIdx 即 web fongmi_live_idx（Live 初始化即读）。
 */
class LiveStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("libretvaz_live", Context.MODE_PRIVATE)

    fun favs(): List<LiveFav> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_FAV, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                LiveFav(o.optString("name"), o.optString("url"), o.optString("group").ifBlank { null }, o.optInt("srcIdx"))
            }
        }
    }.getOrDefault(emptyList())

    fun favUrls(srcIdx: Int): Set<String> = favs().filter { it.srcIdx == srcIdx }.map { it.url }.toSet()

    /** 切收藏（web toggleLiveFav）：url+srcIdx 判重；返回切换后是否已收藏 */
    fun toggleFav(srcIdx: Int, chan: LiveChannel): Boolean {
        val list = favs().toMutableList()
        val at = list.indexOfFirst { it.url == chan.url && it.srcIdx == srcIdx }
        val added = at < 0
        if (added) {
            list.add(0, LiveFav(chan.name, chan.url, chan.group, srcIdx))
            if (list.size > 100) repeat(list.size - 100) { list.removeAt(list.lastIndex) }
        } else {
            list.removeAt(at)
        }
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().put("name", f.name).put("url", f.url).apply {
                f.group?.let { put("group", it) }
                put("srcIdx", f.srcIdx)
            })
        }
        prefs.edit().putString(KEY_FAV, arr.toString()).apply()
        return added
    }

    /** 上次频道（web fongmi_live_last） */
    fun last(): Pair<Int, String>? = runCatching {
        val o = JSONObject(prefs.getString(KEY_LAST, "") ?: "")
        val url = o.optString("url")
        if (url.isEmpty()) null else o.optInt("srcIdx") to url
    }.getOrNull()

    fun setLast(srcIdx: Int, url: String) {
        prefs.edit().putString(KEY_LAST, JSONObject().put("srcIdx", srcIdx).put("url", url).toString()).apply()
    }

    fun srcIdx(): Int = prefs.getInt(KEY_SRC, 0)

    fun setSrcIdx(i: Int) = prefs.edit().putInt(KEY_SRC, i).apply()

    companion object {
        const val LIVE_FAV_GROUP = "★收藏"
        private const val KEY_FAV = "fav"
        private const val KEY_LAST = "last"
        private const val KEY_SRC = "srcIdx"
    }
}

/**
 * 直播运行时上下文（web Live 状态的播放页视图）：列表页起播/切组时填充，
 * PlayerScreen 直播模式据此做 ↑↓ 换台、数字选台与信息条。
 */
object LiveCtx {
    var wantAuto = false // 进直播视图标志：频道表就绪后自动续播上次台（web renderLive→playChan 语义）
    var srcIdx = -1
    var srcName = ""
    var chans: List<LiveChannel> = emptyList()
    var cat: String? = null        // 播放时所在国家分类（null=全部）
    var group: String? = null      // 播放时所在组（null=全部）
    var favOnly = false            // 播放时在看 ★收藏
    var favUrls: Set<String> = emptySet()
    var cur = -1                   // chans 下标
    var epgIdx: PerfCore.EpgIndex? = null

    /** 换台池=当前可见列表（分类→分组→收藏逐级过滤）。偏离注：web 在 ★收藏 组 inGroup 恒空（↑↓ 失效），APK 按可见列表可换台；
     * B16 同名多线合并：只留首个=最快线（精筛表按速度降序输出），其余同名条目经 linesOf 作备用线路 */
    fun visible(): List<Int> = chans.indices.filter { i ->
        val ch = chans[i]
        when {
            favOnly -> favUrls.contains(ch.url)
            cat != null && LiveGeo.catOf(ch.tvgId) != cat -> false
            else -> (group == null || ch.group == group) && chans.indexOfFirst { it.name == ch.name } == i
        }
    }

    /** 同名台的全部线路（chans 下标，列表序=速度降序） */
    fun linesOf(i: Int): List<Int> {
        val n = chans.getOrNull(i)?.name ?: return emptyList()
        return chans.indices.filter { chans[it].name == n }
    }

    /** web chanStep：池内相对 cur 前进/后退，环绕；
     *  A11（审计 2026-11-11）：播同名备用线时 cur 不在合并池——先按台名锚定池内首线条目再步进，
     *  否则 pos=-1 环绕跳错台（step(1) 原地=看着卡死）+列表高亮丢；名不在池仍按 web 原式进池首 */
    fun step(d: Int): Int? {
        val vis = visible()
        if (vis.isEmpty()) return null
        var pos = vis.indexOf(cur)
        if (pos < 0) chans.getOrNull(cur)?.name?.let { n ->
            pos = vis.indexOfFirst { chans[it].name == n }
        }
        return vis[(pos + d + vis.size) % vis.size]
    }
}
