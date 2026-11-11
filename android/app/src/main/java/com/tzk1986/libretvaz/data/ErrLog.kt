package com.tzk1986.libretvaz.data

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 内部日志（用户 2026-11-03 建；2026-11-04 改 7 天滚动保留）：内存环形缓冲+DiskCache 落盘，
 * 退出 app/清缓存不清，超 7 天自动清除，仅设置页「清空日志」手动清；同 tag+内容 30s 去重防单源风暴刷屏；单条 180 字符截断。
 * 只记未知错误与后台维护流水（guard）：死源超时/连接重置/播放 IO 失败等可预期网络错误由 isExpectedNet 过滤，不进日志。
 */
object ErrLog {
    private const val MAX = 200
    private const val MSG_MAX = 180
    private const val DEDUP_MS = 30_000L
    private const val KEEP_MS = 7 * 24 * 3600_000L
    private const val DISK_KEY = "errlog"
    private val lines = ArrayDeque<Pair<Long, String>>()
    private val lastSeen = HashMap<String, Long>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private var loaded = false

    /** 首访懒加载落盘分片，逐行 `epoch\t正文`，超 7 天的丢弃 */
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val (_, payload) = DiskCache.read(DISK_KEY) ?: return
            val now = System.currentTimeMillis()
            payload.lineSequence().forEach {
                val tab = it.indexOf('\t')
                if (tab <= 0) return@forEach
                val t = it.substring(0, tab).toLongOrNull() ?: return@forEach
                if (now - t <= KEEP_MS) lines.addLast(t to it.substring(tab + 1))
            }
        }
    }

    private fun persist() = runCatching {
        DiskCache.write(DISK_KEY, System.currentTimeMillis(), lines.joinToString("\n") { "${it.first}\t${it.second}" })
    }

    /** 审计 2026-11-11 P1-2：源 URL/域名=私有数据（日志设置页可见+落盘 7 天+可截屏/adb 拉取）。
     *  入口统一脱敏：http(s) URL 整体替换为「源[sha1(host)前8hex]」——host 同则哈希同，跨条可关联排障，域名零泄露 */
    fun sanitize(msg: String): String = URL_RE.replace(msg) { m ->
        val host = m.value.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val hash = runCatching {
            java.security.MessageDigest.getInstance("SHA-1").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        }.getOrElse { "00000000" }
        "源[$hash]"
    }

    private val URL_RE = Regex("https?://[^\\s\"'<>]+")

    fun add(tag: String, msg: String) {
        synchronized(this) {
            load()
            val now = System.currentTimeMillis()
            val safe = sanitize(msg)
            val key = tag + safe
            val prev = lastSeen[key]
            if (prev != null && now - prev < DEDUP_MS) return
            lastSeen[key] = now
            if (lastSeen.size > 400) lastSeen.clear()
            val m = if (safe.length > MSG_MAX) safe.substring(0, MSG_MAX) + "…" else safe
            lines.addLast(now to "${fmt.format(Date(now))} [$tag] $m")
            while (lines.size > MAX) lines.removeFirst()
            while (lines.isNotEmpty() && now - lines.first().first > KEEP_MS) lines.removeFirst()
            persist()
        }
    }

    fun add(tag: String, e: Throwable) = add(tag, "${e.javaClass.simpleName}: ${e.message}")

    /** 可预期网络类错误（死源超时/重置/DNS/播放 IO 失败）=不算程序报错，跳过记录 */
    fun isExpectedNet(e: Throwable): Boolean = e is java.net.SocketTimeoutException ||
        e is java.net.ConnectException || e is java.net.UnknownHostException ||
        e is java.net.SocketException || e is java.io.IOException ||
        e is kotlinx.coroutines.TimeoutCancellationException

    fun size(): Int = synchronized(this) { load(); lines.size }

    fun text(): String = synchronized(this) {
        load()
        if (lines.isEmpty()) "（暂无报错日志）" else lines.joinToString("\n") { it.second }
    }

    /** DiskCache.clearAll（清缓存）会连带删掉 errlog 盘片，事后调用把内存日志回写保 7 天承诺 */
    fun repersist() = synchronized(this) { load(); persist() }

    fun clear() = synchronized(this) {
        lines.clear(); lastSeen.clear(); loaded = true
        runCatching { DiskCache.removePrefix(DISK_KEY) }
    }
}
