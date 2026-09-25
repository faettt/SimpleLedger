package com.simpleledger.app.sync

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.sync.photo.NetworkStatus

/**
 * 触发挂接（V3 实测定案）：
 * - **COLD_START**：`MainActivity.onCreate` 直接 `requestSync(COLD_START)`；
 * - **FOREGROUND**：本对象挂 `ProcessLifecycleOwner` ON_START（回前台），
 *   去抖 60s 在 `SyncManager.requestSync` 内；
 * - **PERIODIC**：[SyncWorker.enqueue]（30min + NETWORK CONNECTED）；
 * - **MANUAL**：设置页「立即同步」→ `syncNow()`。
 */
object SyncTriggers {

    /** 回前台触发挂接（`LedgerApp.onCreate` 调用一次） */
    fun attach(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    val syncApp = app as? LedgerApp ?: return
                    syncApp.container.syncManager.requestSync(SyncTrigger.FOREGROUND)
                }
            }
        )
    }
}

/**
 * [NetworkStatus] 的系统实现（ConnectivityManager）。
 *
 * 「Wi-Fi」口径（R-17）：`TRANSPORT_WIFI` 或 `TRANSPORT_ETHERNET`——以太网同样
 * 不计蜂窝流量，与「仅 Wi-Fi 传照片」的用户意图一致。
 * 需要 `ACCESS_NETWORK_STATE` 普通权限（T-1 已声明，无运行时弹窗）。
 */
class AndroidNetworkStatus(context: Context) : NetworkStatus {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
