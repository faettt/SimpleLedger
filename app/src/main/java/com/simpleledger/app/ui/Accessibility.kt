package com.simpleledger.app.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.simpleledger.app.util.Money

/**
 * 是否「跟随系统减少动效」。
 *
 * 自研动效体系见 `ui/theme/Motion.kt`（v2「有重量的纸」），减少动效是**两层降级**：
 *
 * · **时长层（自动）**：所有自研动画都走 Compose 动画原语（`animate*AsState` /
 *   `Animatable` / `tween` / `spring`），Compose UI 内部的 `MotionDurationScale`
 *   自动监听 `Settings.Global.ANIMATOR_DURATION_SCALE` 并把缩放因子设为 0，
 *   动画随之瞬时完成（弹簧同样被缩放）。**不允许在调用点换算时长**——那样会与
 *   Compose 内部机制重复，且在 0.5x 等慢速档下行为错误。
 * · **形变层（显式）**：缩放、位移这类**装饰性形变**时长缩放管不到（0ms 的缩放仍是缩放），
 *   由本 [LocalReduceMotion] 显式分流：`slPress` 的按压缩放归零（保留涟漪）、
 *   `slHoverLift` 整体关闭、转场位移/缩放降级为纯淡化（降级判断内建在新 API 里）。
 *   Material3 内置动画（`ModalBottomSheet` / `DropdownMenu` /
 *   `AlertDialog` / `DatePicker` / 涟漪）同样走时长层，不重包。
 *
 * 系统侧开关即 `Settings.Global.ANIMATOR_DURATION_SCALE`：为 0 即关闭动画。
 * 本 [LocalReduceMotion] 经 [rememberReduceMotion] 提供实时值，切换开发者选项后无需重启。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 读取系统「移除动画」偏好：`ANIMATOR_DURATION_SCALE == 0` 视为开启减少动效。
 *
 * 用 `ContentObserver` 监听变化：用户在开发者选项里切换后无需重启应用即可生效。
 * `DisposableEffect` 保证离开组合时反注册，不泄漏。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduceMotion by remember { mutableStateOf(resolver.isAnimationDisabled()) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = resolver.isAnimationDisabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduceMotion
}

/** 系统动画时长缩放是否为 0（即关闭）。读取失败按「未关闭」处理，避免误关导致行为突变。 */
private fun ContentResolver.isAnimationDisabled(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * 语义辅助：把「支出 / 收入」方向词与中文金额拼成读屏串。
 *
 * 视觉上方向靠 `−`/`+` 与颜色区分；但读屏念的是文本，`−`/`+` 不会转化成「支出/收入」，
 * 颜色更无法朗读。故方向词必须由这里显式给出。
 *
 * @param isIncome true 收入 / false 支出
 * @param cents 金额（分）
 * @param hidden 隐私模式下为 true：**绝不朗读真实金额**，只读「金额已隐藏」
 */
fun amountSpeech(isIncome: Boolean, cents: Long, hidden: Boolean): String {
    val direction = if (isIncome) "收入" else "支出"
    return if (hidden) "$direction，金额已隐藏" else "$direction${Money.toChineseSpeech(cents)}"
}