package com.tzk1986.libretvaz.data.perf

import android.content.Context

/**
 * CJK 拼音首字母查表（审计 2026-11-11 P0-1：替换 ICU4J 运行时，debug 包省 ~10.3MiB ICU 数据）。
 * 表=构建期离线预生成（JDK 单文件 + icu4j classpath 跑锚点区间法，与 PinIndex 原算法逐字一致）：
 * 覆盖 0x3400..0x4DBF（扩展 A 6592 字）+ 0x4E00..0x9FFF（基本区 20992 字）共 27,584 字节，
 * 值为小写首字母 a~z 或 0（区间内但锚点未命中，如部分生僻 Ext A）。
 * 生产端不再引 ICU；testImplementation icu4j 由 PinTableParityTest 全表逐字校验打包文件防漂移。
 */
object PinTable {
    const val ASSET_NAME = "pin_index.bin"
    const val SIZE = 27584
    private const val EXT_A_START = 0x3400
    private const val EXT_A_COUNT = 6592

    @Volatile
    private var table: ByteArray? = null

    val ok: Boolean get() = table != null

    /** 装机/单测共用入口：尺寸不符视为坏表不装（保持未装态=无拼音降级，与原 coll 缺失路径一致） */
    fun install(bytes: ByteArray?) {
        if (bytes != null && bytes.size == SIZE) table = bytes
    }

    /** 幂等：首次从随包 assets 读表并安装（VocabPools 创建路径调用）；读失败静默降级 */
    fun ensureLoaded(context: Context) {
        if (ok) return
        val bytes = runCatching {
            context.applicationContext.assets.open(ASSET_NAME).use { input ->
                val buf = ByteArray(SIZE)
                var off = 0
                while (off < SIZE) {
                    val n = input.read(buf, off, SIZE - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == SIZE) buf else null
            }
        }.getOrNull()
        install(bytes)
    }

    /** CJK 首字母查表：命中 a~z；范围外（非 CJK）与未装机返回 NUL */
    fun initialOf(ch: Char): Char {
        val t = table ?: return 0.toChar()
        val idx = when (ch.code) {
            in EXT_A_START until (EXT_A_START + EXT_A_COUNT) -> ch.code - EXT_A_START
            in 0x4E00..0x9FFF -> EXT_A_COUNT + ch.code - 0x4E00
            else -> return 0.toChar()
        }
        return t[idx].toInt().toChar()
    }
}
