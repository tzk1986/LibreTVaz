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
        private const val KEY_PROXY = "proxy_url"
    }
}
