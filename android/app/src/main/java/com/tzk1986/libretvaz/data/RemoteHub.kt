package com.tzk1986.libretvaz.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 扫码远程输入中枢（TVBox 同构，用户 2026-11-10 裁决）：APK 自托管迷你 HTTP 服务（RemoteServer，9978），
 * 不再依赖调试台服务器/服务器地址配置——二维码直接指本机真实局域网 IP，手机扫码即开内置 H5 输入页。
 * runLoop 常驻 MainActivity：起服务 + 轮询收件队列，按注册序派发给当前可见页（search 收词即搜；源页收订阅按 qrAuto）。
 */
object RemoteHub {
    private val inbox = RemoteInbox()
    // P0-3 会话 token：进程级随机 12 hex，只随二维码/H5 页传播——同网段未扫码设备无法伪造 /send
    private val token: String = runCatching {
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }.getOrDefault(System.nanoTime().toString(16))
    private val server = RemoteServer(inbox, token)
    private val handlers = CopyOnWriteArraySet<(mode: String, text: String) -> Boolean>()

    /** 手机可达基址 http://<wlan-ip>:9978；空=未连 WiFi/无局域网地址 */
    @Volatile var base: String = ""
        private set

    fun qrUrl(mode: String): String = "$base/remote?m=$mode&k=$token"

    /** 页面注册命中回调：返回 true=已消费（一条只被一个页面吃掉）；随页面生命周期注销 */
    fun addHandler(h: (mode: String, text: String) -> Boolean): () -> Unit {
        handlers.add(h)
        return { handlers.remove(h) }
    }

    /** 起服务并轮询收件；作用域取消即结束 */
    suspend fun runLoop(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            val ip = lanIp()
            base = if (ip.isEmpty()) "" else "http://$ip:${RemoteServer.PORT}"
            server.start()
        }
        while (scope.isActive) {
            val item = withContext(Dispatchers.IO) { inbox.poll() }
            if (item != null) for (h in handlers) runCatching { if (h(item.first, item.second)) break }
            else delay(500)
        }
    }
}
