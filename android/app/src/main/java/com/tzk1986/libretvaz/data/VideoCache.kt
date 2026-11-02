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

    @Synchronized
    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(obtain(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Synchronized
    fun clear(context: Context) {
        runCatching { cache?.release() }
        cache = null
        runCatching { File(context.cacheDir, "video").deleteRecursively() }
    }

    fun sizeBytes(context: Context): Long =
        File(context.cacheDir, "video").walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
