package com.simpleledger.app.ui.security

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.ui.theme.SlType

/**
 * 后台停留多久才重新验证（毫秒）。
 *
 * 30 秒是体验底线：切出去看一眼通知、接个短电话就回来，不应被再次拦下——那会让应用锁
 * 从「保护」变成「骚扰」，用户最终会到系统里把它关掉，等于没做。真正的威胁场景是手机
 * 被放下、离开视线，那通常远超 30 秒。
 */
private const val LOCK_AFTER_BACKGROUND_MILLIS = 30_000L

/**
 * 判断设备当前是否能完成一次认证（生物识别 **或** 设备凭据）。
 *
 * 用 `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` 与真正弹出验证时所用的组合**完全一致**——
 * 两者若不一致，会出现「开关能开、验证弹不出」的死锁，把用户挡在应用外面。
 * 没录指纹但设了 PIN / 图案的机器，`DEVICE_CREDENTIAL` 兜底，同样返回可认证。
 */
fun canAuthenticate(context: Context): Boolean {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(context).canAuthenticate(authenticators) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * 应用锁闸门：包在应用最外层，锁定时以全屏遮罩盖住全部内容（账目一像素都不渲染），
 * 解锁后放行。用条件渲染而非 `zIndex`——遮罩下的真实内容根本不该进入组合树。
 *
 * 触发时机只有两个（见 [LOCK_AFTER_BACKGROUND_MILLIS]）：
 * 1. **冷启动**：进入组合时若开关已开，直接置为锁定；
 * 2. **后台超过 30 秒返回**：`ProcessLifecycleOwner` 记录 `ON_STOP` 时刻，`ON_START` 时比较差值。
 *    记录用 `SystemClock.elapsedRealtime()`（单调时钟），不受用户改系统时间影响。
 *
 * 关掉开关时**立即解锁**：用户刚在「我的」页主动关闭，不应反被锁在外面。
 */
@Composable
fun AppLockGate(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lockEnabled by settings.appLock.collectAsState()

    // 冷启动即按当前开关取值；开关关闭时也确保不锁（避免关闭后仍停在锁屏）
    var locked by remember { mutableStateOf(lockEnabled) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 观察者只注册一次（DisposableEffect(Unit)），但需要读到**最新**的开关值，
    // 否则闭包会固化注册那一刻的旧值。rememberUpdatedState 保证读到的始终是当前值。
    val enabledNow by rememberUpdatedState(lockEnabled)
    val lastStoppedAt = remember { longArrayOf(0L) }

    DisposableEffect(Unit) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lastStoppedAt[0] = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                val stoppedAt = lastStoppedAt[0]
                val backgroundedTooLong =
                    stoppedAt != 0L &&
                        SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_BACKGROUND_MILLIS
                if (enabledNow && backgroundedTooLong) {
                    errorText = null
                    locked = true
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }

    // 开关关闭 → 立即解锁；开启时保持当前状态（不打扰正在使用中的用户）
    DisposableEffect(lockEnabled) {
        if (!lockEnabled) {
            locked = false
            errorText = null
        }
        onDispose {}
    }

    if (!locked) {
        content()
        return
    }

    // LocalContext 在 Compose 里通常是 ContextThemeWrapper，不直接是 Activity；
    // 必须逐层解包 ContextWrapper 才能拿到 FragmentActivity，否则 BiometricPrompt 无法创建。
    val activity = context.findFragmentActivity()
    val unlockTitle = stringResource(R.string.app_lock_title)
    val unlockSubtitle = stringResource(R.string.app_lock_subtitle)
    val unsupportedText = stringResource(R.string.app_lock_unsupported)
    val noCredentialHint = stringResource(R.string.app_lock_no_credential_hint)

    // BiometricPrompt 需要 FragmentActivity；此处必须是 Activity 上下文
    val prompt = remember(activity) {
        activity?.let { act ->
            BiometricPrompt(
                act,
                ContextCompat.getMainExecutor(act),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        errorText = null
                        locked = false
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        when (errorCode) {
                            // 用户主动取消不算错误，不报红、不静默刷屏
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> Unit

                            // 「设备没有可用凭据」类错误：系统原文（多为英文）对用户没有行动价值，
                            // 换成可操作的中文指引——告诉用户去设置锁屏密码，而不是丢一句看不懂的报错。
                            // 覆盖 TOCTOU：设置开关时还有凭据，之后被用户在系统里删掉了。
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            -> errorText = noCredentialHint

                            // 其余错误（硬件占用、锁死、超时等）保留系统原文，信息更具体
                            else -> errorText = errString.toString()
                        }
                    }
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                // 品牌名 = 页面标题装饰位（楷体 Regular，decorativeWeightRule）
                style = SlType.headline,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = unlockTitle,
                style = SlType.title,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = unlockSubtitle,
                style = SlType.bodySm,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    errorText = null
                    if (prompt == null || activity == null) {
                        errorText = unsupportedText
                        return@Button
                    }
                    // TOCTOU 复查：开启开关时的校验会过时——用户可能之后在系统设置里删掉了
                    // 锁屏密码/指纹。此处再查一次，无凭据就直接给可操作指引，不去调 authenticate
                    // （否则只会弹回一句看不懂的系统原文，用户以为应用坏了）。
                    // 注意：刻意不提供「一键关闭应用锁」的逃生口——能删掉系统锁屏凭据的人已经解开了
                    // 手机锁，此时应用锁是最后一道防线；给指引即可，加绕过口会削弱安全性。
                    if (!canAuthenticate(context)) {
                        errorText = noCredentialHint
                        return@Button
                    }
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(unlockTitle)
                        .setSubtitle(unlockSubtitle)
                        // 与 canAuthenticate 用同一组合；不可用 BIOMETRIC_STRONG（会排除部分机型），
                        // 也不设 negativeButtonText——设备凭据模式下该字段互斥（build 会抛异常）
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build()
                    prompt.authenticate(info)
                },
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.app_lock_unlock), style = SlType.label)
            }

            errorText?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = SlType.bodySm,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 逐层解包 `ContextWrapper`，取到承载本窗口的 [FragmentActivity]。
 *
 * Compose 的 `LocalContext` 一般是 `ContextThemeWrapper`，直接 `as? FragmentActivity`
 * 会得到 null —— 那样锁屏永远弹不出验证，等于把自己关在应用外。这里沿 delegate 链向上找。
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}