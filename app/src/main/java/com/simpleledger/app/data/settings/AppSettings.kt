package com.simpleledger.app.data.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用偏好设置。使用 SharedPreferences 持久化 + StateFlow 暴露，
 * 主题、动态取色、隐藏金额等偏好变化时 UI 会立即响应。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _hideAmounts = MutableStateFlow(prefs.getBoolean(KEY_HIDE_AMOUNTS, false))
    val hideAmounts: StateFlow<Boolean> = _hideAmounts.asStateFlow()

    private val _appLock = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setHideAmounts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_AMOUNTS, enabled).apply()
        _hideAmounts.value = enabled
    }

    fun setAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLock.value = enabled
    }

    object ThemeMode {
        const val SYSTEM = "system"
        const val LIGHT = "light"
        const val DARK = "dark"

        val all = listOf(SYSTEM, LIGHT, DARK)

        fun label(mode: String): String = when (mode) {
            LIGHT -> "浅色"
            DARK -> "深色"
            else -> "跟随系统"
        }
    }

    private companion object {
        const val PREFS_NAME = "simple_ledger_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_HIDE_AMOUNTS = "hide_amounts"
        const val KEY_APP_LOCK = "app_lock"
    }
}

/**
 * 是否隐藏金额（隐私模式）。由 MainActivity 注入，金额组件据此显示为 ••••。
 */
val LocalHideAmounts = staticCompositionLocalOf { false }
