package com.tzk1986.libretvaz.data

import android.content.Context

import java.io.File

/**
 * 海报图片缓存 3 天 TTL 清理（用户 2026-11-04 需求：首页/资源口海报墙已加载图直接复用，超 3 天清理；
 * 再补充：磁盘缓存命中即 touch 延期——按「最后使用时间」计 3 天，3 天内用到的图不删）。
 * Coil 磁盘条目文件名 = sha256(cacheKey).hex() + ".0"(数据)/".1"(元数据)；journal 指向已删条目时仅未命中重拉，无害。
 */
object ImageGc {
    const val TTL_MS = 3 * 24 * 3600_000L

    fun purge(context: Context) {
        val dir = File(context.cacheDir, "image_cache")
        if (!dir.isDirectory) return
        val cut = System.currentTimeMillis() - TTL_MS
        dir.walkBottomUp().forEach { f ->
            if (f.isFile && f.name != "journal.journal" && f.lastModified() < cut) runCatching { f.delete() }
        }
    }

    /** 图片缓存当前占用（设置页清缓存报回收体积用） */
    fun sizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, "image_cache")
        return if (dir.isDirectory) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    /** 全清图片缓存（内存+磁盘）：设置页「清理缓存」钮调用（用户 2026-11-04 要求覆盖图片缓存） */
    fun clear(context: Context) {
        val loader = coil.Coil.imageLoader(context)
        loader.diskCache?.clear()
        loader.memoryCache?.clear()
    }

    /** 磁盘缓存命中续期：mtime=now（Coil EventListener 在 IO 读线程回调，两文件 stat+touch 成本可忽略）。
     *  条目文件名=与 Coil 一致的 sha256(cacheKey) hex（小写）+ ".0"/".1" */
    fun touch(context: Context, cacheKey: String?) {
        if (cacheKey.isNullOrEmpty()) return
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "image_cache")
        for (suffix in listOf(".0", ".1")) {
            val f = File(dir, hex + suffix)
            if (f.isFile) runCatching { f.setLastModified(System.currentTimeMillis()) }
        }
    }
}
