package com.simpleledger.app.di

import android.content.Context
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.repo.ImageStorage
import com.simpleledger.app.data.repo.LedgerRepository

/** 轻量手工依赖注入容器：单模块应用的务实选择 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val imageStorage: ImageStorage = ImageStorage(context)
    val repository: LedgerRepository = LedgerRepository(database, imageStorage)
}
