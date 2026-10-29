package com.tzk1986.libretvaz.data

import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 阻塞式 GET，调用方需在 IO 线程使用 */
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", CHROME_UA)
            conn.setRequestProperty("Accept", "application/json, */*")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
