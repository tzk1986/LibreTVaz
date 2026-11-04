package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import org.json.JSONArray
import java.net.URL

/**
 * 专区（资源口）地基（web fongmi.js ZONE_SEED_VER/seedZoneSources/alignZonePool 等价）：
 * 预置站单=本地私有 assets/zone_sources.json（[name,url] 数组，.git/info/exclude 排除，仓库仅空表 .example）；
 * 公开克隆读不到文件=空列表，本文件所有逻辑整体静默跳过，功能不报错。
 */
object Zone {
    /** 站单扩充后升此版本号触发补种（对齐 web 'v4'） */
    const val SEED_VER = "v4"

    /** 分类名关键词判专区（对齐 web isAdultType，导入时源名命中即直接归专区池） */
    val ADULT_KEYWORDS = listOf(
        "伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "日本福利片", "日本伦理片",
        "擦边短剧", "擦边", "韩国伦理", "里番动漫", "门事件", "制服诱惑", "国产传媒", "cosplay",
        "黑丝诱惑", "无码", "日本无码", "有码", "日本有码", "SWAG", "网红主播", "色情片", "同性片",
        "福利视频", "无码高清", "有码高清", "91视频", "自拍偷拍", "mini传媒", "萝莉少女",
    )

    fun isAdultType(t: String?): Boolean = !t.isNullOrEmpty() && ADULT_KEYWORDS.any { t.contains(it) }

    fun hostOf(u: String?): String? = try { URL(u).host.lowercase() } catch (e: Exception) { null }

    /** 读本地私有站单；缺失/损坏=空表（公开克隆形态） */
    fun loadPreset(context: Context): List<VodSource> = runCatching {
        val text = context.assets.open("zone_sources.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            val url = a.optString(1)
            if (url.isBlank()) null else VodSource(a.optString(0).ifBlank { hostOf(url) ?: url }, url.trim(), isAdult = true)
        }
    }.getOrDefault(emptyList())

    /** 首次/升版本进资源口补种预置站（版本号闸门；用户删过不再重复加回；「恢复预置」清标记重种） */
    fun seedIfAbsent(context: Context, store: SourceStore) {
        val settings = SettingsStore(context)
        if (settings.zoneSeedVer == SEED_VER) return
        settings.zoneSeedVer = SEED_VER
        val preset = loadPreset(context)
        if (preset.isEmpty()) return
        val have = store.vodSources().map { PerfCore.norm(it.url) }.toHashSet()
        preset.filter { PerfCore.norm(it.url) !in have }.forEach { store.addVod(it) }
    }

    /**
     * 归池收编（只升不降）：主机名命中站单的点播源强制 isAdult=true（同站路径变体也收进）；
     * 专区源之间仅按精确 URL 去重（路径变体保留互为备份）；当前源下标越界钳位。站单空=整体跳过。
     * 返回是否有实际改动。任何路径都不得把专区源降回普通源。
     */
    fun align(context: Context, store: SourceStore): Boolean {
        val preset = loadPreset(context)
        if (preset.isEmpty()) return false
        val zoneHosts = preset.mapNotNull { hostOf(it.url) }.toHashSet()
        if (zoneHosts.isEmpty()) return false
        val before = store.vodSources()
        val forced = before.map { s ->
            val h = hostOf(s.url)
            if (h != null && h in zoneHosts && !s.isAdult) s.copy(isAdult = true) else s
        }
        val seen = HashSet<String>()
        val deduped = forced.filter { s ->
            val h = hostOf(s.url)
            if (h == null || h !in zoneHosts) return@filter true
            seen.add(PerfCore.norm(s.url))
        }
        if (deduped == before) return false
        store.replaceVod(deduped)
        val settings = SettingsStore(context)
        if (settings.siteIdx >= deduped.count { !it.isAdult }) settings.siteIdx = 0
        return true
    }
}
