package com.tzk1986.libretvaz.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    /** 用户 2026-11-06 走查 #1：全 app 普通 GET 与播放数据源共用一个连接池（keep-alive 复用消除逐请求 TCP+TLS 握手） */
    val sharedPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    /** client 按「代理×超时」组合缓存（proxyUrl 变更后组合键自然换建新 client） */
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    private fun clientFor(timeoutMs: Int): OkHttpClient {
        val spec = proxySpec()
        val key = "${spec?.first}:${spec?.second}|$timeoutMs"
        clients[key]?.let { return it }
        if (clients.size > 16) clients.clear() // minor#7：组合键枚举本应有限（代理单值×固定超时），防动态超时调用方无限膨胀；sharedPool 在 object 级不受 clear 影响
        val c = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(spec?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it.first, it.second)) } ?: Proxy.NO_PROXY)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return clients.putIfAbsent(key, c) ?: c
    }

    /** 阻塞 GET 内核：手写 ≤6 跳跨协议重定向循环（Location 相对解析同原 HttpURLConnection 路）；IO 线程使用 */
    private inline fun <R> execute(url: String, timeoutMs: Int, headers: Map<String, String>, read: (Response) -> R): R {
        val client = clientFor(timeoutMs)
        var cur = url
        repeat(6) {
            val rb = Request.Builder().url(cur)
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { resp ->
                val code = resp.code
                if (code in 200..299) return read(resp)
                if (code in 300..399) {
                    val loc = resp.header("Location")
                    if (loc.isNullOrEmpty()) throw IllegalStateException("HTTP $code")
                    cur = try { URL(URL(cur), loc).toString() } catch (e: Exception) { loc }
                    return@repeat
                }
                throw IllegalStateException("HTTP $code")
            }
        }
        throw IllegalStateException("重定向次数过多")
    }

    /** 阻塞式二进制 GET（XMLTV .gz 等需先按魔数判压缩，不能预先按文本读）。OkHttp 透明解压 gzip：Epg 两态均可处理 */
    fun getBytes(url: String, timeoutMs: Int = 15000): ByteArray =
        execute(url, timeoutMs, mapOf("User-Agent" to CHROME_UA)) { it.body?.bytes() ?: ByteArray(0) }

    /** 阻塞式 GET，调用方需在 IO 线程使用 */
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = CHROME_UA
        headers["Accept"] = "application/json, */*"
        headers.putAll(extraHeaders)
        // 恒按 UTF-8 解码，与原 bufferedReader(Charsets.UTF_8) 语义逐字一致（不看 Content-Type charset）
        return execute(url, timeoutMs, headers) { String(it.body?.bytes() ?: ByteArray(0), Charsets.UTF_8) }
    }
}
