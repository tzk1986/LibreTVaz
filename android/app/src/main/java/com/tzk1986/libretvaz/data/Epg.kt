package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 直播节目单（web ensureEpg/fetchEpgText 等价）：源配置 epg=XMLTV 地址，gzip 魔数自动解压；
 * 解析走 PerfCore.parseXmltv；进程内缓存 5 分钟（对齐 EPG_TTL），失败静默降级（无 EPG 不阻塞起播）。
 */
object Epg {
    private const val TTL = 5 * 60_000L
    private val cache = MemCache<PerfCore.EpgIndex>(8)

    // D41（审计 2026-11-11）：gzip 解压带上限（解压炸弹防护——小压缩流可炸出 GB 级内存）
    private const val MAX_XMLTV_BYTES = 64L * 1048576

    private fun gunzipCapped(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { g ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = g.read(buf)
                if (n == -1) break
                total += n
                if (total > MAX_XMLTV_BYTES) throw IllegalStateException("EPG 解压超 ${MAX_XMLTV_BYTES / 1048576}MB 上限")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    suspend fun load(url: String?): PerfCore.EpgIndex? {
        val u = url?.takeIf { it.isNotBlank() } ?: return null
        cache.get(u, TTL)?.let { return it }
        return withContext(Dispatchers.IO) {
            val idx = runCatching {
                val bytes = Http.getBytes(u)
                val text = if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte())
                    String(gunzipCapped(bytes), Charsets.UTF_8)
                else String(bytes, Charsets.UTF_8)
                PerfCore.parseXmltv(text)
            }.getOrNull()
            idx?.let { cache.set(u, it) }
            idx
        }
    }

    /** 频道今日节目（当日过滤在 PerfCore.epgFor 内）；index 未就绪/无匹配返回空 */
    fun dayList(index: PerfCore.EpgIndex?, chanName: String?): List<PerfCore.EpgProgram> =
        PerfCore.epgFor(index, chanName)

    /** 「正在」行；n=nowNum 便于单测注入 */
    fun now(index: PerfCore.EpgIndex?, chanName: String?, n: Long = PerfCore.nowNum()): PerfCore.EpgProgram? =
        PerfCore.epgNow(PerfCore.epgFor(index, chanName), n)

    /** YYYYMMDDHHMM[SS] 数值 → HH:mm（web epHm 等价；不足 12 位视为无时间信息） */
    fun hm(t: Long): String {
        val s = t.toString()
        return if (s.length < 12) "--:--" else "${s.substring(8, 10)}:${s.substring(10, 12)}"
    }
}
