package com.tzk1986.libretvaz.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ServerStatus(
    val version: String,
    val passwordRequired: Boolean,
    val verified: Boolean,
    val defaultSourceCount: Int,
    val defaultLiveSourceCount: Int,
    val defaultSubscriptionCount: Int,
)

sealed class StatusResult {
    data class Success(val status: ServerStatus) : StatusResult()
    data class Failure(val message: String) : StatusResult()
}

object ApiStatusClient {
    suspend fun fetchStatus(serverUrl: String): StatusResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext StatusResult.Failure("请先填写服务器地址")
        val endpoint = serverUrl.trimEnd('/') + "/api/status"
        try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                return@withContext StatusResult.Failure("HTTP $code：请确认是 LibreTV 服务地址")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            StatusResult.Success(
                ServerStatus(
                    version = json.optString("version", "未知"),
                    passwordRequired = json.optBoolean("passwordRequired", false),
                    verified = json.optBoolean("verified", false),
                    defaultSourceCount = json.optJSONArray("defaultSources")?.length() ?: 0,
                    defaultLiveSourceCount = json.optJSONArray("defaultLiveSources")?.length() ?: 0,
                    defaultSubscriptionCount = json.optJSONArray("defaultSubscriptions")?.length() ?: 0,
                )
            )
        } catch (e: Exception) {
            StatusResult.Failure("连接失败：${e.javaClass.simpleName} ${e.message}")
        }
    }
}
