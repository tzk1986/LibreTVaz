package com.tzk1986.libretvaz

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tzk1986.libretvaz.data.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * B11 应用入口：注入磁盘缓存根并冷启动兜底清理（进程被杀后过期分片不残留），
 * 挂 30min 周期硬清任务（对齐 web pruneExpiredCache 每 30 分钟；视频 SimpleCache 由 evictor 自愈不另管）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 全局兜底：未捕获异常先记一行到内存报错日志再交回系统处理（日志不落盘，进程亡即清）
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            com.tzk1986.libretvaz.data.ErrLog.add("fatal", "${t.name} ${e.javaClass.simpleName}: ${e.message}")
            def?.uncaughtException(t, e)
        }
        DiskCache.init(this)
        // B3 测速网络指纹需要 ConnectivityManager（用户 2026-11-05 批准）
        com.tzk1986.libretvaz.data.perf.LineSpeed.init(this)
        // 用户 2026-11-06 走查 #8：词库双池 restore（读盘+8000 词 ICU 拼音派生）原落在首屏首次
        // addWords 的主线程上 → 冷启动后台预热，UI 首次取用时已就绪（VocabPools.get 持锁，并发安全）
        Thread { runCatching { com.tzk1986.libretvaz.data.VocabPools.get(this) } }.start()
        // 用户 2026-11-04：海报墙图统一走单例 ImageLoader——磁盘缓存 200MB LRU，
        // 同 URL 冷启动/翻页直接复用不再重拉；超 3 天条目由 ImageGc 清除（冷启动+周期任务）
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                // 用户 2026-11-06 走查 #12：海报到位硬切跳变 → 淡入过渡（磁盘命中同受 effect 管，短暂 alpha 无感）
                .crossfade(true)
                .diskCache(
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(200L * 1024 * 1024)
                        .build(),
                )
                // 用户 2026-11-04：磁盘命中即续期（3 天 TTL 按最后使用时间计，常用海报不被清）
                .eventListener(object : coil.EventListener {
                    override fun fetchEnd(
                        request: coil.request.ImageRequest,
                        fetcher: coil.fetch.Fetcher,
                        options: coil.request.Options,
                        result: coil.fetch.FetchResult?,
                    ) {
                        if ((result as? coil.fetch.SourceResult)?.dataSource == coil.decode.DataSource.DISK)
                            com.tzk1986.libretvaz.data.ImageGc.touch(this@App, options.diskCacheKey)
                    }
                })
                .build(),
        )
        // 用户 2026-11-04：延后 60s 再清——先让首屏海报经磁盘命中续期（touch），真正 3 天没用到的才进垃圾堆
        Thread {
            runCatching { Thread.sleep(60_000); com.tzk1986.libretvaz.data.ImageGc.purge(this) }
        }.start()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cmsCacheGc", ExistingPeriodicWorkPolicy.UPDATE,
            // 首轮延 1 分钟：开机即入队会撞包扫描/冷启动风暴致 onStartJob 超时 ANR（模拟器实测），此后按 30min 周期
            PeriodicWorkRequestBuilder<CacheGcWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(1, TimeUnit.MINUTES).build(),
        )
    }
}

class CacheGcWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            DiskCache.purgeExpired()
            DiskCache.trimTo(DiskCache.MAX_BYTES)
            // 用户 2026-11-04：海报图缓存 3 天 TTL 随周期 GC 一并清理
            com.tzk1986.libretvaz.data.ImageGc.purge(applicationContext)
        }
        Result.success()
    }
}
