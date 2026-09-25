package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.photo.PhotoSyncReport

/**
 * 同步状态机（U-4 四态角标，§3.7）：Never / Idle / Syncing[phase] / Failed。
 *
 * S6 静默契约：数据层**永不抛异常给 UI**，失败只落 [SyncState.Failed] 角标 +
 * `SyncStore.markSyncError`（枚举名）；文案由 UI 层从 `strings.xml` 取（§7-7）。
 */
sealed class SyncState {
    /** 从未同步（空心圆） */
    data object Never : SyncState()

    /** 正常（✓） */
    data object Idle : SyncState()

    /** 同步中（旋转墨点），[phase] = 当前阶段 */
    data class Syncing(val phase: SyncPhase) : SyncState()

    /** 失败（「!」，静默）；[at] = 失败时刻（epoch millis） */
    data class Failed(val reason: SyncError, val at: Long) : SyncState()
}

/** 一轮同步的四个阶段（§4.1：PUSH_OPS → PULL_OPS → MERGE → PHOTOS） */
enum class SyncPhase { PUSH_OPS, PULL_OPS, MERGE, PHOTOS }

/** 同步触发四路（V3 实测挂接）：冷启动 / 回前台 / 30min 周期 / 手动 */
enum class SyncTrigger { COLD_START, FOREGROUND, PERIODIC, MANUAL }

/**
 * 同步错误枚举。**名称与 `DavErrors` 的字符串常量逐字对应**（`toSyncErrorName` 的
 * 映射表即本枚举名契约，不得漂移）；持久化也存名称（`SyncPrefs.lastError`）。
 */
enum class SyncError {
    /** 网络不可用 / 服务端瞬态（可重试） */
    NETWORK,

    /** 凭证错（401/403/407；403 在坚果云可能是超额语义，U-4 待实测） */
    AUTH,

    /** 同步口令不一致（KCV 校验失败，R-04） */
    BAD_PASSWORD,

    /** 云盘空间 / 流量不足（413/507/509） */
    QUOTA,

    /** 云端数据已损坏 / 格式不符 */
    CORRUPTED,

    /** 条件写冲突（412） */
    CONFLICT_WRITE,

    /** 兜底 */
    UNKNOWN,
    ;

    companion object {
        /** 枚举名 → 枚举（持久化反查）；未知名称兜底 [UNKNOWN] */
        fun fromName(name: String): SyncError = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * 一轮同步的结果统计。
 *
 * S6 契约：失败也以返回值表达（[success] = false + [error]），**不抛异常**；
 * [skipped] = true 表示本轮未执行（未配置同步 / 单飞占用）。
 */
data class SyncOutcome(
    val success: Boolean,
    val error: SyncError? = null,
    val skipped: Boolean = false,
    /** PUSH 上传的操作条数 */
    val pushedOps: Int = 0,
    /** PULL 拉回的新分片数 */
    val pulledChunks: Int = 0,
    /** MERGE 物化的操作数 */
    val appliedOps: Int = 0,
    /** MERGE 末尾仍挂起的操作数（引用未到，§3.5-6） */
    val deferredOps: Int = 0,
    /** PHOTOS 阶段报告（Wi-Fi 门控 / 去重 / 续传，R-16/17/19/23） */
    val photo: PhotoSyncReport = PhotoSyncReport.EMPTY,
) {
    companion object {
        /** 未配置同步 / 单飞占用时的空跑结果 */
        val SKIPPED = SyncOutcome(success = true, skipped = true)
    }
}

/**
 * 同步本地状态持久化端口（生产 = `account.SyncPrefs`；JVM 单测 = 内存实现）。
 *
 * 刻意只暴露引擎/照片管线需要的最小面：KDF 派生材料等敏感字段不进端口，
 * 由 `SyncManager` 直接持有 `SyncPrefs` 读写（取舍见 §7-10：凭证与派生材料仅存本地）。
 */
interface SyncStore {
    /** 设备身份（操作 actorId）；首次读取自动生成 */
    val deviceId: String

    /** 本机认领成员 syncId；null = 未认领 */
    var selfMemberId: String?

    /** 「仅 Wi-Fi 传照片」（R-17，默认 true）；设备本地偏好，不参与同步 */
    var wifiOnlyPhotos: Boolean

    /** 上次同步成功时刻（epoch millis；0 = 从未同步） */
    var lastSyncAt: Long

    /** 最近错误的枚举名（如 "BAD_PASSWORD"）；null = 无错误 */
    var lastError: String?

    /** 同步成功收尾：盖时间戳、清错误 */
    fun markSyncSuccess(atMillis: Long)

    /** 同步失败收尾：只记错误枚举名，不弹任何 UI（S6） */
    fun markSyncError(errorName: String)

    /**
     * setupAccount 收尾：一次性持久化 KDF 参数 + KCV + 派生密钥
     * （T-4 裁量：自动同步需本地持钥，§7-10 取舍；忘口令 = [clearAll]）。
     */
    fun saveDerived(keys: SyncKeys, kdf: KdfParams)

    /** 取回派生密钥三件套；未接入同步返回 null */
    fun loadKeys(): SyncKeys?

    /** 取回 KDF 参数；未设置口令（盐缺失）返回 null */
    fun kdfParams(): KdfParams?

    /** 照片流量记账（R-19）；返回记账后的 (本月上行, 本月下行) */
    fun addPhotoTraffic(upBytes: Long, downBytes: Long): Pair<Long, Long>

    /** 本月照片流量读数 (上行, 下行) */
    fun monthlyPhotoUsage(): Pair<Long, Long>

    /** 重置同步（R-05）：清空全部同步侧状态（本地账本零触碰） */
    fun clearAll()
}
