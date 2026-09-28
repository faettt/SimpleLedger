package com.simpleledger.app.sync.account

/**
 * U-8 明文传输知情确认门（纯决策逻辑：无 Android 依赖，JVM 可测）。
 *
 * 职责边界：只回答「这次动作前要不要拦下来先问用户」——警示事件发射、确认弹窗与
 * 挂起动作续跑是 UI/VM 编排，不在此层（见 `SyncSettingsViewModel.gateOnInsecureHttp`）。
 *
 * 记忆口径（U-8 定案）：用户**知情确认一次 = 本会话内不再重复确认**——会话级记忆而非
 * 按 URL 记忆（确认后换一个 http 地址也不重问）；内存态即可，进程重启后重新确认一次
 * 并不过分。**取消不落记忆**：知情确认必须是主动选择，取消后下一次动作照拦。
 *
 * 畸形 URL（无 scheme、空串等）不归此门管：[WebDavCred.validate] 是格式校验的
 * 唯一拦截点，门只依据 [isPlaintextHttp] 回答明文风险有无。
 */
class InsecureHttpGate(
    /** 明文判定注入点（默认 [isPlaintextHttp]；测试可注入自定义判定） */
    private val judge: (String) -> Boolean = ::isPlaintextHttp,
) {

    /** 门决策：[Ask] = 拦下先确认；[Pass] = 放行（无明文风险，或本会话已确认） */
    sealed interface Decision {
        data object Ask : Decision
        data object Pass : Decision
    }

    private var confirmedThisSession = false

    fun decide(url: String): Decision = when {
        !judge(url) -> Decision.Pass // 非 http：无明文传输风险，不涉及知情确认
        confirmedThisSession -> Decision.Pass // 本会话已知情确认（U-8：不再重复问）
        else -> Decision.Ask
    }

    /** 用户知情选择继续；此后本会话内不再询问 */
    fun confirm() {
        confirmedThisSession = true
    }
}
