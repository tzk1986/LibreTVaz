package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import org.json.JSONArray

/**
 * 内置点播源（对齐 web fongmi.js BUILTIN_SOURCES）：站单=本地私有 assets/builtin_sources.json
 * （[name,url] 数组，.git/info/exclude 排除，仓库仅空表 .example，同 Zone 模式，用户 2026-11-04 保密改造）；
 * 公开克隆读不到文件=空列表（无源版），源管理页「导入点播清单」可恢复备份文件；
 * 首启/升版本自动种入（版本闸门，用户删过不再回填）；源管理「添加内置已测源」可手动补缺。
 */
object BuiltinSources {
    // v2（用户 2026-11-06）：站单新增量子/索尼两源，升闸门让既有安装首启补缺
    const val SEED_VER = "v2"

    /** 纯解析（供导入/asset 两处复用）：[["name","url"],...]，坏条目跳过，名称空退主机名 */
    fun parsePreset(text: String): List<VodSource> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            val url = a.optString(1).trim()
            if (url.isBlank()) null else VodSource(a.optString(0).ifBlank { Zone.hostOf(url) ?: url }, url)
        }
    }.getOrDefault(emptyList())

    fun loadPreset(context: Context): List<VodSource> = runCatching {
        parsePreset(context.assets.open("builtin_sources.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(emptyList())

    /** 补缺式添加（norm 去重，同 web drawSrcMgr 添加内置源）；D40 批量化=单次全表重写；返回实加数 */
    fun addMissing(context: Context, store: SourceStore): Int {
        val have = store.vodSources().map { PerfCore.norm(it.url) }.toHashSet()
        return store.addVodAll(loadPreset(context).filter { PerfCore.norm(it.url) !in have }).also {
            if (it > 0) store.alignZone()
        }
    }

    /** E44（审计 2026-11-11 #44）：闸门「加源成功后再写」——空站单（无源版）不烧闸门，私有包装入后下次冷启补种。
     *  D40：逐源 addVod（N 次全量 decode+encode+apply）改 addVodAll 单次重写，首启冷启主线程开销 O(n²)→O(n)；
     *  归池不在此处做——调用方（MainActivity）下一句即 alignZone，避免双跑 */
    fun seedIfAbsent(context: Context, store: SourceStore) {
        val settings = SettingsStore(context)
        val preset = loadPreset(context)
        if (SeedGate.plan(settings.builtinSeedVer, SEED_VER, preset.size) != SeedGate.Action.SEED_AND_STAMP) return
        val have = store.vodSources().map { PerfCore.norm(it.url) }.toHashSet()
        store.addVodAll(preset.filter { PerfCore.norm(it.url) !in have })
        settings.builtinSeedVer = SEED_VER
    }

    /**
     * 内置直播源（web 无直播预置，本站扩展）。首启种入两条（公开地址，无保密需要）：
     * ①「iptv 全球精筛·内置」= assets/live_world.m3u（本机四步管线测速筛后打包，静态）；
     * ②「iptv 中国·在线」= iptv-org 官方每日重建的中国子表（在线拉取即最新）。
     */
    const val LIVE_SEED_VER = "v1"
    val LIST_LIVE = listOf(
        LiveSource("iptv 全球精筛·内置", "asset://live_world.m3u"),
        LiveSource("iptv 中国·在线", "https://iptv-org.github.io/iptv/countries/cn.m3u"),
    )

    fun seedLiveIfAbsent(context: Context, store: SourceStore) {
        val settings = SettingsStore(context)
        if (settings.liveSeedVer == LIVE_SEED_VER) return
        settings.liveSeedVer = LIVE_SEED_VER
        val have = store.liveSources().map { PerfCore.norm(it.url) }.toHashSet()
        store.addLiveAll(LIST_LIVE.filter { PerfCore.norm(it.url) !in have }) // D40：同点播批量化
    }
}
