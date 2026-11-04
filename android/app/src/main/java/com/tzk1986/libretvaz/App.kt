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
        }
        Result.success()
    }
}
