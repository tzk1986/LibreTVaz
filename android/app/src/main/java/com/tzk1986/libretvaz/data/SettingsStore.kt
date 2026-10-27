package com.tzk1986.libretvaz.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("libretvaz", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}
