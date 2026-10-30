package com.tzk1986.libretvaz.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL

object Http {
    const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 可选 HTTP 代理「host:port」，空=直连；App 启动时由设置注入，ExoPlayer 侧读同样字段 */
    @Volatile var proxyUrl: String = ""

    fun proxySpec(): Pair<String, Int>? {
        val i = proxyUrl.lastIndexOf(':')
        if (i <= 0) return null
        val host = proxyUrl.substring(0, i).trim()
        val port = proxyUrl.substring(i + 1).trim().toIntOrNull() ?: return null
        return if (host.isNotEmpty()) host to port else null
    }

    /**
     * media3 DefaultHttpDataSource 无 setProxy API，但它底层走 HttpURLConnection，
     * 会尊重 JVM 全局 ProxySelector——TV 盒无系统代理时用这个给播放也套上代理。
     * 局域网/本机地址恒直连，避免代理不可达时连自家服务器也断。
     */
    // Android 的 okhttp 连接层不接受 null selector（报 proxySelector == null），须存原始值还原
    private val systemSelector: ProxySelector? = ProxySelector.getDefault()

    fun applyGlobalProxy() {
        val spec = proxySpec()
        if (spec == null) { ProxySelector.setDefault(systemSelector); return }
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(spec.first, spec.second))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> =
                mutableListOf(if (isLocalHost(uri.host)) Proxy.NO_PROXY else proxy)
            override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, failure: IOException?) {}
        })
    }

    private fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return true
        if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (!host.contains('.')) return true
        val p = host.split('.')
        if (p.firstOrNull() == "127" || p.firstOrNull() == "10") return true
        if (p.size == 4 && p[0] == "192" && p[1] == "168") return true
        if (p.size == 4 && p[0] == "172" && (p[1].toIntOrNull() ?: -1) in 16..31) return true
        return false
    }

    /** 阻塞式 GET，调用方需在 IO 线程使用 */
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String {
        val proxy = proxySpec()?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it.first, it.second)) }
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
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
