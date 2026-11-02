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

    suspend fun load(url: String?): PerfCore.EpgIndex? {
        val u = url?.takeIf { it.isNotBlank() } ?: return null
        cache.get(u, TTL)?.let { return it }
        return withContext(Dispatchers.IO) {
            val idx = runCatching {
                val bytes = Http.getBytes(u)
                val text = if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte())
                    String(GZIPInputStream(ByteArrayInputStream(bytes)).readBytes(), Charsets.UTF_8)
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
