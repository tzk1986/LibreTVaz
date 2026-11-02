package com.tzk1986.libretvaz.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 扫码远程输入中枢（web RemoteIn/remoteLoop 等价）：对接调试台服务器的 remote 系列接口会话协议——
 * open 注册 → poll 长轮询（≤30s 挂起、手机 send 即唤醒；poll 兼作心跳，未在线时手机页 send 被拒）。
 * 单循环挂 MainActivity，命中按注册序派发给当前可见页（search 页收词即搜；源页收订阅按 qrAuto 直导/确认）。
 * 二维码地址用服务器局域网 IP：serverUrl 为本机回环时回落 /api/remote/base 的 host:port（手机须能访问）。
 */
object RemoteHub {
    val sid: String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** 解析后的服务器基址（http://host:port）；空=服务器未配置/未解析 */
    @Volatile var base: String = ""
        private set

    private val handlers = CopyOnWriteArraySet<(mode: String, text: String) -> Boolean>()

    fun qrUrl(mode: String): String = "$base/remote?id=$sid&m=$mode"

    /** 页面注册命中回调：返回 true=已消费（一条只被一个页面吃掉）；随页面生命周期注销 */
    fun addHandler(h: (mode: String, text: String) -> Boolean): () -> Unit {
        handlers.add(h)
        return { handlers.remove(h) }
    }

    /** 长轮询主循环：在协程作用域内挂起运行，作用域取消即结束；服务器地址变更时由调用方重启 */
    suspend fun runLoop(serverUrl: String, scope: CoroutineScope) {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) { base = ""; return }
        val b = withContext(Dispatchers.IO) { resolveBase(trimmed) }
        base = b
        withContext(Dispatchers.IO) { runCatching { postJson("$b/api/remote/open", JSONObject().put("sid", sid).toString()) } }
        while (scope.isActive) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val j = JSONObject(Http.get("$b/api/remote/poll?id=$sid", timeoutMs = 35_000))
                    val t = j.optString("text")
                    if (t.isEmpty()) null else j.optString("mode", "search") to t
                }
            }
            when {
                r.isFailure -> delay(3000) // 断连/超时：退避后续轮
                r.getOrNull() != null -> {
                    val (mode, text) = r.getOrThrow()!!
                    for (h in handlers) runCatching { if (h(mode, text)) break }
                }
                else -> Unit // 30s 空回：服务器在线，立即续轮
            }
        }
    }

    /** 回环地址 QR 必须换成服务器局域网 IP，否则手机打不开（web remoteInit 同修正） */
    private fun resolveBase(serverUrl: String): String {
        val host = runCatching { URL(serverUrl).host }.getOrNull().orEmpty()
        val loopback = host.isEmpty() || host.startsWith("127.") || host == "localhost" || host == "::1" || host == "0.0.0.0"
        if (!loopback) return serverUrl
        return runCatching {
            val j = JSONObject(Http.get("$serverUrl/api/remote/base"))
            val h = j.optString("host").ifEmpty { return@runCatching serverUrl }
            "http://$h:" + j.optInt("port", 8787)
        }.getOrDefault(serverUrl)
    }

    private fun postJson(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", Http.CHROME_UA)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }
}
