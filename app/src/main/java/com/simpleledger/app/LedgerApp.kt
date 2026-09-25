package com.simpleledger.app

import android.app.Application
import com.simpleledger.app.di.AppContainer
import com.simpleledger.app.sync.SyncTriggers
import com.simpleledger.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            // 上次会话中选了图但没保存的缓存
            container.imageStorage.cleanPendingImages()
            // 上次会话中删除账目、但撤销窗口还没结束就被杀进程留下的暂存贴图
            container.imageStorage.cleanParkedFiles()
        }
        // U-6：存量照片 contentHash 补算（静默后台；补算完成前只同步账目，照片引用不丢）
        applicationScope.launch {
            runCatching { container.repository.backfillImageHashes() }
        }
        // T-4 触发挂接（V3）：回前台去抖触发 + 30min 周期兜底（COLD_START 在 MainActivity）
        SyncTriggers.attach(this)
        SyncWorker.enqueue(this)
    }
}
