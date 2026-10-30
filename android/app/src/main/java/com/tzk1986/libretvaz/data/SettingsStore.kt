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

    /** 「host:port」可选 HTTP 代理——TV 盒无系统代理时救活被 reset 的源站；空=直连 */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROXY, value.trim()).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTO_NEXT = "auto_next"
        private const val KEY_AUTO_LINE = "auto_line"
        private const val KEY_RESUME = "resume_play"
        private const val KEY_PROXY = "proxy_url"
    }
}
