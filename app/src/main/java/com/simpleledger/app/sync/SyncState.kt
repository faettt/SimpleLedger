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

/**
 * 同步触发五路（V3 实测挂接 + P0-1 写后触发）：冷启动 / 回前台 / 30min 周期 / 手动 / 写后。
 *
 * [AFTER_WRITE] 由本地写路径（`OpRecorder` 三个写入口）在业务事务内排程，
 * 走**独立于 60s 自动窗口**的合并窗口（`SyncManager.WRITE_DEBOUNCE_MILLIS`）。
 */
enum class SyncTrigger { COLD_START, FOREGROUND, PERIODIC, MANUAL, AFTER_WRITE }

/**
 * 「本轮未执行」的成因（P1-3）：`SyncOutcome.skipped` 原先一个 bool 混了两因，
 * UI 无法区分「没配同步」和「已有一轮在跑」。
 *
 * - [NOT_CONFIGURED]：无凭证 / 无派生密钥，引擎拿不到远端（同步本就未接入）。
 * - [IN_FLIGHT]：单飞锁被占用（同一时刻只跑一轮），本轮被挡回，稍后会有结果。
 */
enum class SkipReason { NOT_CONFIGURED, IN_FLIGHT }

/**
 * 同步错误枚举。**名称与 `DavErrors` 的字符串常量逐字对应**（`toSyncErrorName` 的
 * 映射表即本枚举名契约，不得漂移）；持久化也存名称（`SyncPrefs.lastError`）。
 */
enum class SyncError {
    /** 网络不可用 / 服务端瞬态（可重试） */
    NETWORK,

    /** 凭证错（401/407；**401 才是凭证码**——403 已按 U-4 定稿分流到 [ACCESS_DENIED]） */
    AUTH,

    /**
     * 服务端拒绝访问（403，U-4/T4 定稿）。⚠️ 403 有至少四种与密码无关的语义：
     * 本月流量用尽（社区实证）/ 同步目录不可写（不要直接用根目录）/ 账号开了二次验证 / 风控频控。
     * 因此**不能**并进 [QUOTA]——那会把目录配置与安全设置误诊成额度不足，只是把误诊换个方向。
     * 非瞬态：不自动重试（`SyncWorker` 只对 [NETWORK] 重试），等用户动作。
     */
    ACCESS_DENIED,

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
 * [skipped] = true 表示本轮未执行（未配置同步 / 单飞占用），成因见 [skipReason]。
 */
data class SyncOutcome(
    val success: Boolean,
    val error: SyncError? = null,
    val skipped: Boolean = false,
    /** [skipped] = true 时的成因；非跳过轮为 null（P1-3：UI 按成因分措辞） */
    val skipReason: SkipReason? = null,
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
    /** 本轮收尾时仍处隔离状态的损坏分片数（U-7：不挡其余同步，状态详情透出） */
    val quarantinedChunks: Int = 0,
) {
    companion object {
        /**
         * 「未配置同步」空跑结果（保留旧名兼容既有调用方，如 `SyncWorker` / 单测假件）。
         *
         * P1-3 起语义收窄为「未配置」：旧的二义性 bool 由 [skipReason] 消歧，
         * 单飞占用改用 `skip(SkipReason.IN_FLIGHT)`。这样「[skipped] = true ⇒
         * [skipReason] 非空」的不变量在场内处处成立。
         */
        val SKIPPED = SyncOutcome(success = true, skipped = true, skipReason = SkipReason.NOT_CONFIGURED)

        /** 带成因的空跑结果（P1-3）：引擎两处 skipped 产出点分别传 «未配置» / «单飞占用» */
        fun skip(reason: SkipReason): SyncOutcome =
            SyncOutcome(success = true, skipped = true, skipReason = reason)
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

    // —— U-7 损坏分片隔离：连续解码失败计数 + 隔离名单 ——

    /**
     * 记一次分片拉取/解码失败，返回该分片**连续**失败次数。
     * 计数而非首败即隔离：下载截断等偶发损坏表现与真损坏同形（GCM 认证失败），
     * 达 [com.simpleledger.app.sync.SyncEngine.CHUNK_QUARANTINE_THRESHOLD] 才隔离。
     */
    fun recordChunkFailure(chunkName: String): Int

    /** 分片成功解码：清零失败计数（隔离名单由引擎按手动重试口径另行维护） */
    fun clearChunkFailure(chunkName: String)

    /** 某分片当前连续失败次数 */
    fun chunkFailureCount(chunkName: String): Int

    /** 当前被隔离的分片名（连续失败达阈值；自动轮跳过回拉，手动「立即同步」重试） */
    fun quarantinedChunks(): Set<String>

    /** 把分片加入隔离名单 */
    fun quarantineChunk(chunkName: String)

    /** 解除某分片隔离（手动同步重试前调用；仍损坏则按计数当场重新隔离） */
    fun clearQuarantinedChunk(chunkName: String)

    /** 重置同步（R-05）：清空全部同步侧状态（本地账本零触碰） */
    fun clearAll()
}
