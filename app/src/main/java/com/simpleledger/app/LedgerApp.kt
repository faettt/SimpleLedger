package com.simpleledger.app

import android.app.Application
import com.simpleledger.app.di.AppContainer
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
    }
}
