package com.simpleledger.app

import android.app.Application
import com.simpleledger.app.di.AppContainer

class LedgerApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
