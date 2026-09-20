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

    private val _secureScreen = MutableStateFlow(prefs.getBoolean(KEY_SECURE_SCREEN, false))
    val secureScreen: StateFlow<Boolean> = _secureScreen.asStateFlow()

    /** 快捷金额档位（单位：分）；空位用 0 表示，展示时跳过 */
    private val _quickAmounts = MutableStateFlow(parseQuickAmounts(prefs.getString(KEY_QUICK_AMOUNTS, null)))
    val quickAmounts: StateFlow<List<Long>> = _quickAmounts.asStateFlow()

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

    fun setSecureScreen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
        _secureScreen.value = enabled
    }

    /**
     * 保存快捷金额档位。固定 3 个槽位，0 表示留空——
     * 预置 10/50/100 元是为了让功能在新装时就可被发现（空控件等于不存在的功能）。
     */
    fun setQuickAmounts(values: List<Long>) {
        val normalized = (values + List(3) { 0L }).take(3)
        prefs.edit().putString(KEY_QUICK_AMOUNTS, normalized.joinToString(",")).apply()
        _quickAmounts.value = normalized
    }

    /** 展示用：过滤掉空档位 */
    fun visibleQuickAmounts(): List<Long> = _quickAmounts.value.filter { it > 0 }

    private fun parseQuickAmounts(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return DEFAULT_QUICK_AMOUNTS
        val parsed = raw.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (parsed.isEmpty()) return DEFAULT_QUICK_AMOUNTS
        return (parsed + List(3) { 0L }).take(3)
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
        const val KEY_SECURE_SCREEN = "secure_screen"
        const val KEY_QUICK_AMOUNTS = "quick_amounts"

        /** 默认档位：10 / 50 / 100 元 */
        val DEFAULT_QUICK_AMOUNTS = listOf(1_000L, 5_000L, 10_000L)
    }
}

/**
 * 是否隐藏金额（隐私模式）。由 MainActivity 注入，金额组件据此显示为 ••••。
 */
val LocalHideAmounts = staticCompositionLocalOf { false }
