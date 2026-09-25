package com.simpleledger.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.sync.SyncTrigger
import com.simpleledger.app.ui.AppRoot
import com.simpleledger.app.ui.rememberReduceMotion
import com.simpleledger.app.ui.security.AppLockGate
import com.simpleledger.app.ui.theme.SimpleLedgerTheme

/**
 * 唯一 Activity。
 *
 * 继承 [FragmentActivity]（而非 `ComponentActivity`）是**应用锁**的要求：
 * `androidx.biometric.BiometricPrompt` 构造器只接受 `FragmentActivity`，它会借此挂载
 * 承载提示的 Fragment。`FragmentActivity` 是 `ComponentActivity` 的子类，因此
 * `setContent` / `enableEdgeToEdge` 等扩展照常可用，其余行为不变。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LedgerApp).container
        val settings = container.settings
        // T-4 冷启动同步触发（S6：异步静默，失败只动角标，绝不弹窗）
        container.syncManager.requestSync(SyncTrigger.COLD_START)

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            val dynamicColor by settings.dynamicColor.collectAsState()
            val hideAmounts by settings.hideAmounts.collectAsState()
            val secureScreen by settings.secureScreen.collectAsState()

            // 系统「移除动画」偏好：Compose 内置动画已自动遵循 ANIMATOR_DURATION_SCALE，
            // 这里读出来只用于下发给应用自研动效的 LocalReduceMotion（当前无消费方，保持 0 影响）
            val reduceMotion = rememberReduceMotion()

            // 截屏保护跟随开关实时生效（不重启 Activity）：
            // FLAG_SECURE 既禁止截屏/录屏，也让「最近任务」里的应用预览显示为空白，
            // 否则切到后台时，卡片缩略图会把账目金额亮在系统界面里。
            LaunchedEffect(secureScreen) {
                if (secureScreen) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.LIGHT -> false
                AppSettings.ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }

            CompositionLocalProvider(LocalHideAmounts provides hideAmounts) {
                SimpleLedgerTheme(
                    darkTheme = darkTheme,
                    dynamicColor = dynamicColor,
                    reduceMotion = reduceMotion,
                ) {
                    // 应用锁闸门包在最外层：锁定时整个 AppRoot（含全部账目）都不进入组合树，
                    // 而不是「渲染好再用遮罩盖住」——后者内存里仍有画面，也会被截屏/预览抓到。
                    AppLockGate(settings = settings) {
                        AppRoot()
                    }
                }
            }
        }
    }
}