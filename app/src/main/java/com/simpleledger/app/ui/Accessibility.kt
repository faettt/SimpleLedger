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
 * 全项目**零自研动画**（无 `animate*` / `tween` / `spring` / `Animatable`），需要处理的是
 * Material3 内置动画（`ModalBottomSheet` / `DropdownMenu` / `AlertDialog` / `DatePicker` /
 * `TimePicker` / 图片查看浮层 / 导航选中指示器）。
 *
 * 系统侧的系统动画开关对应 `Settings.Global.ANIMATOR_DURATION_SCALE`：为 0 即关闭动画。
 * 这一条 Compose **已自动遵循**——`ui` 模块内部的 `MotionDurationScaleImpl` 会监听该设置并
 * 把 `MotionDurationScale` 的缩放因子设为 0，所有基于 `animate*AsState` / `Animatable` 的
 * 动画随之瞬时完成。因此**不需要**再写一套「动画时长系统」，更不能去换算时长——那样反而
 * 会与 Compose 的内部机制重复。
 *
 * 本 [LocalReduceMotion] 的职责是**给项目自己需要按动效偏好分流的少数场景**一个统一入口，
 * 默认 `false`（不影响任何现有行为）。当前消费方见 [EntryRow] 的长按提示等读屏文案场景；
 * 若将来引入自研动效，应统一读这里而不是各自读系统设置。
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