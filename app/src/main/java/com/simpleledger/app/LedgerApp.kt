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
        // 清理上次会话中选了图但未保存的缓存文件，避免垃圾累积
        applicationScope.launch { container.imageStorage.cleanPendingImages() }
    }
}
