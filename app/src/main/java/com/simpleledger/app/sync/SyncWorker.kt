package com.simpleledger.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.simpleledger.app.LedgerApp
import java.util.concurrent.TimeUnit

/**
 * 30 分钟周期兜底同步（V3 定案：WorkManager 下限 15 分钟确认，取 30 分钟）。
 *
 * 约束：仅 `NETWORK CONNECTED` 时运行。S6 静默：Worker **无任何 UI**，
 * 失败口径——网络错 `Result.retry()`（系统退避），其余错误 `Result.success()`
 * （凭证错/口令错重试无意义，避免重试风暴；角标已由引擎落 `Failed`）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = (applicationContext as? LedgerApp)?.container?.syncManager ?: return Result.success()
        val outcome = manager.syncNow(SyncTrigger.PERIODIC) // S6：永不抛异常
        return when {
            outcome.skipped -> Result.success() // 未配置 / 单飞占用
            outcome.success -> Result.success()
            outcome.error == SyncError.NETWORK -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /** 唯一周期任务名（KEEP 语义：重复 enqueue 不叠加） */
        const val WORK_NAME = "simpleledger-sync"

        /** 注册周期任务（幂等；`LedgerApp.onCreate` 调用一次） */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 同步周期（分钟，V3 定案 30min） */
        const val SYNC_INTERVAL_MINUTES = 30L
    }
}
