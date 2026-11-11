package com.tzk1986.libretvaz.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频播放盘缓存（B11）：media3 SimpleCache 200MB LRU 封顶（HLS 分片/MP4 Range 回源才落盘），
 * 杜绝播片无限吃硬盘；FLAG_IGNORE_CACHE_ON_ERROR——缓存读写异常直连回源不炸播放。
 * SimpleCache 与目录单例绑定（多实例会坏索引），清理需 release 后重建。
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 200L shl 20
    private var cache: SimpleCache? = null

    @Synchronized
    private fun obtain(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "video"),
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }

    /** P1-1（审计 2026-11-11）：启动预热单例——DB 打开+索引修剪放 IO 线程做，详情页组合期 factory 命中直返 */
    @Synchronized
    fun prewarm(context: Context) { obtain(context.applicationContext) }

    @Synchronized
    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(obtain(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 清理视频盘缓存。用户 2026-11-05：release 失败（播放器仍持有缓存/目录被占用）时保留单例并返回
     * false——此时若置 null，下次 obtain 会在同目录新建第二个 SimpleCache，撞 media3 的
     * 「SimpleCache is already in use」直接崩掉播放，故宁可不清也不能置空。
     */
    @Synchronized
    fun clear(context: Context): Boolean {
        val c = cache
        if (c != null && runCatching { c.release() }.isFailure) return false
        cache = null
        runCatching { File(context.cacheDir, "video").deleteRecursively() }
        return true
    }

    fun sizeBytes(context: Context): Long =
        File(context.cacheDir, "video").walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
