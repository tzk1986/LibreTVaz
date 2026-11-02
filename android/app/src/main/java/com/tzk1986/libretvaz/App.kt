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
 * 挂 6h 周期过期逐出任务（等价 web 启动扫过期；视频 SimpleCache 由 evictor 自愈不另管）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiskCache.init(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cmsCacheGc", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheGcWorker>(6, TimeUnit.HOURS).build(),
        )
    }
}

class CacheGcWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            DiskCache.purgeExpired()
            DiskCache.trimTo(DiskCache.MAX_BYTES)
        }
        Result.success()
    }
}
