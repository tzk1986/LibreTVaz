package com.tzk1986.libretvaz.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("libretvaz", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    /** 对齐 web fongmi_autonext：本集播完自动连播下一集 */
    var autoNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply()

    /** 对齐 web fongmi_autoline：播放失败自动切换线路 */
    var autoLine: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LINE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LINE, value).apply()

    /** 对齐 web fongmi_resume：跨会话按历史进度续播（内存级进度，进入播放页生效） */
    var resumePlay: Boolean
        get() = prefs.getBoolean(KEY_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME, value).apply()

    /** 对齐 web fongmi_histmerge：历史按片名去重（同名跨源只留最新一条） */
    var histMerge: Boolean
        get() = prefs.getBoolean(KEY_HIST_MERGE, true)
        set(value) = prefs.edit().putBoolean(KEY_HIST_MERGE, value).apply()

    /** 对齐 web fongmi_histmax：历史每池保留条数（30/60/100） */
    var histMax: Int
        get() = prefs.getInt(KEY_HIST_MAX, 60)
        set(value) = prefs.edit().putInt(KEY_HIST_MAX, value).apply()

    /** 对齐 web fongmi_linespeed：播放页对各线路做吞吐采样并显示速度标签 */
    var lineSpeed: Boolean
        get() = prefs.getBoolean(KEY_LINE_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_LINE_SPEED, value).apply()

    /** 对齐 web fongmi_autobestline：测速完成后自动跳到最快线（用户手动换过线则不抢） */
    var autoBestLine: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BEST_LINE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BEST_LINE, value).apply()

    /** 对齐 web fongmi_qrauto：扫码推送的订阅自动导入（关=进源页手动确认；B8 消费） */
    var qrAuto: Boolean
        get() = prefs.getBoolean(KEY_QR_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_QR_AUTO, value).apply()

    /** 对齐 web fongmi_threads：搜索首批并发源数（其余 1.2s 次批） */
    var searchThreads: Int
        get() = prefs.getInt(KEY_THREADS, 8)
        set(value) = prefs.edit().putInt(KEY_THREADS, value.coerceIn(1, 48)).apply()

    /** 对齐 web fongmi_fuzzy：片名核心词容错（关=严格匹配） */
    var fuzzy: Boolean
        get() = prefs.getBoolean(KEY_FUZZY, true)
        set(value) = prefs.edit().putBoolean(KEY_FUZZY, value).apply()

    /** 对齐 web fongmi_autosrc：全线失败自动搜关联换源续播（默认关） */
    var autoSrc: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SRC, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SRC, value).apply()

    /** 对齐 web fongmi_epfull：点选剧集后收起控制器专注全屏看 */
    var epFull: Boolean
        get() = prefs.getBoolean(KEY_EP_FULL, true)
        set(value) = prefs.edit().putBoolean(KEY_EP_FULL, value).apply()

    /** 对齐 web fongmi_cache 档位：0短/1标准/2长/3超长（聚合+搜索 TTL 一组） */
    var cacheTier: Int
        get() = prefs.getInt(KEY_CACHE_TIER, 1).coerceIn(0, CacheTiers.TIERS.size - 1)
        set(value) = prefs.edit().putInt(KEY_CACHE_TIER, value.coerceIn(0, CacheTiers.TIERS.size - 1)).apply()

    /** 对齐 web fongmi_tabs：功能行 [{nav,show}] JSON，顺序即优先级；空=默认序 */
    var tabsJson: String
        get() = prefs.getString(KEY_TABS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TABS, value).apply()

    /** 专区预置站单已种版本号（对应 web fongmi_zone_seed；升 Zone.SEED_VER 触发补种，清标记=恢复预置） */
    var zoneSeedVer: String
        get() = prefs.getString(KEY_ZONE_SEED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ZONE_SEED, value).apply()

    /** 首页点播墙聚合模式（对应 web fongmi_agg；源选择弹窗「🌐 聚合」，默认单源） */
    var aggMode: Boolean
        get() = prefs.getBoolean(KEY_AGG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_AGG_MODE, value).apply()

    /** 当前点播源下标（对应 web fongmi_site_idx；跨页面共享，测速墙点行可切换） */
    var siteIdx: Int
        get() = prefs.getInt(KEY_SITE_IDX, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_SITE_IDX, value.coerceAtLeast(0)).apply()

    /** 「host:port」可选 HTTP 代理——TV 盒无系统代理时救活被 reset 的源站；空=直连 */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROXY, value.trim()).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTO_NEXT = "auto_next"
        private const val KEY_AUTO_LINE = "auto_line"
        private const val KEY_RESUME = "resume_play"
        private const val KEY_HIST_MERGE = "hist_merge"
        private const val KEY_HIST_MAX = "hist_max"
        private const val KEY_LINE_SPEED = "line_speed"
        private const val KEY_AUTO_BEST_LINE = "auto_best_line"
        private const val KEY_SITE_IDX = "site_idx"
        private const val KEY_ZONE_SEED = "zone_seed"
        private const val KEY_PROXY = "proxy_url"
        private const val KEY_QR_AUTO = "qr_auto"
        private const val KEY_THREADS = "search_threads"
        private const val KEY_FUZZY = "fuzzy"
        private const val KEY_AUTO_SRC = "auto_src"
        private const val KEY_EP_FULL = "ep_full"
        private const val KEY_CACHE_TIER = "cache_tier"
        private const val KEY_TABS = "tabs"
        private const val KEY_AGG_MODE = "agg_mode"
    }
}

/** 缓存时长档位（web CACHE_TIERS 等价）：聚合墙 TTL + 搜索分片 TTL 一组切换 */
object CacheTiers {
    data class Tier(val label: String, val agg: Long, val search: Long)

    val TIERS = listOf(
        Tier("短（聚合1分/搜索3分）", 60_000L, 3 * 60_000L),
        Tier("标准（聚合3分/搜索10分）", 3 * 60_000L, 10 * 60_000L),
        Tier("长（聚合10分/搜索30分）", 10 * 60_000L, 30 * 60_000L),
        Tier("超长（聚合30分/搜索2小时）", 30 * 60_000L, 2 * 3600_000L),
    )
}
