package com.simpleledger.app.sync.account

import android.content.Context
import android.util.Base64
import com.simpleledger.app.sync.SyncStore
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * 同步本地持久化（SharedPreferences `simple_ledger_sync`），实现 [SyncStore] 端口。
 *
 * 存储内容：设备身份（[deviceId]，操作 actorId）/ 本机认领成员（[selfMemberId]）/
 * 照片 Wi-Fi 偏好（[wifiOnlyPhotos]，**设备本地**偏好不同步）/ 同步状态（[lastSyncAt]、
 * [lastError]）/ 照片月流量计数（R-19，对齐坚果云免费额度）/ 口令派生材料（KDF 参数 + KCV）
 * / **派生密钥**（[encKey]/[nameKey]，T-4 裁量）。
 *
 * 安全口径（架构 §7-10）：全部字段存应用私有 SharedPreferences（明文）——口令保护的是
 * **云端**，凭证与派生材料「仅存本地、不上传」（R-01）；不引 security-crypto（官方已废弃），
 * root 级威胁不在 PRD 范围。
 *
 * T-4 裁量（派生密钥持久化）：自动同步（Worker/回前台）无法每次要用户输口令，
 * 派生密钥必须落在本地——与 WebDAV 应用密码同级取舍（同 §7-10 root 威胁不在范围）。
 * 忘记同步口令的出路 = [clearAll]（resetSync，R-05）后凭 WebDAV 凭证重新接入。
 */
class SyncPrefs(context: Context) : SyncStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 每装机一次的设备 UUID（`sync_ops.actorId`，LWW 平局按它破）。首次读取自动生成并落盘 */
    override var deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        }

    /** 本机认领的成员 syncId（U-2）；null = 尚未认领 */
    override var selfMemberId: String?
        get() = prefs.getString(KEY_SELF_MEMBER_ID, null)
        set(value) {
            prefs.edit().putString(KEY_SELF_MEMBER_ID, value).apply()
        }

    /** 「仅 Wi-Fi 传照片」（R-17，默认开）；设备本地偏好，**不参与同步** */
    override var wifiOnlyPhotos: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY_PHOTOS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY_PHOTOS, value).apply()
        }

    /** 上次同步成功时刻（epoch millis；0 = 从未同步） */
    override var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()
        }

    /**
     * 最近一次同步错误：存 `SyncError` 枚举**名**（如 "BAD_PASSWORD"）；null = 无错误。
     *
     * 以字符串持久化是有意的接缝：`SyncError` 定义在 T-4 的 `sync/SyncState.kt`，
     * 本类不反向依赖 UI 侧状态机；`SyncError.fromName(lastError ?: ...)` 映射即可。
     * 文案永远由 UI 层从 `strings.xml` 取（数据层不写死文案，§7-7）。
     */
    override var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_ERROR, value).apply()
        }

    /** 月流量计数所属月份键（"yyyyMM"）；跨月自动归零（见 [addPhotoTraffic]） */
    var monthlyKey: String
        get() = prefs.getString(KEY_MONTHLY_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_MONTHLY_KEY, value).apply()
        }

    /** 本月照片**上行**字节数（R-19）；跨月读取自动归零 */
    var monthlyPhotoUp: Long
        get() = if (inCurrentMonth()) prefs.getLong(KEY_MONTHLY_UP, 0L) else 0L
        set(value) {
            prefs.edit().putLong(KEY_MONTHLY_UP, value).apply()
        }

    /** 本月照片**下行**字节数（R-19）；跨月读取自动归零 */
    var monthlyPhotoDown: Long
        get() = if (inCurrentMonth()) prefs.getLong(KEY_MONTHLY_DOWN, 0L) else 0L
        set(value) {
            prefs.edit().putLong(KEY_MONTHLY_DOWN, value).apply()
        }

    /**
     * 记账照片流量并处理跨月归零（PhotoTransfer 每完成一次上/下行调用一次）。
     * 返回记账后的 (本月上行, 本月下行)。
     */
    override fun addPhotoTraffic(upBytes: Long, downBytes: Long): Pair<Long, Long> {
        val key = currentMonthKey()
        val carried = if (monthlyKey == key) monthlyPhotoUp to monthlyPhotoDown else 0L to 0L
        val up = carried.first + upBytes.coerceAtLeast(0L)
        val down = carried.second + downBytes.coerceAtLeast(0L)
        prefs.edit()
            .putString(KEY_MONTHLY_KEY, key)
            .putLong(KEY_MONTHLY_UP, up)
            .putLong(KEY_MONTHLY_DOWN, down)
            .apply()
        return up to down
    }

    /** 本月照片流量读数 (上行, 下行)，口径与 [addPhotoTraffic] 一致（R-19） */
    override fun monthlyPhotoUsage(): Pair<Long, Long> = monthlyPhotoUp to monthlyPhotoDown

    // —— U-7 损坏分片隔离状态 ——
    // 失败计数存单键 JSON 对象 {分片名: 连续失败次数}（分片名是 32hex 假名，键无泄露面）；
    // 隔离名单用 StringSet。两者都随 clearAll() 一并清空（resetSync 逃生口径）。

    override fun recordChunkFailure(chunkName: String): Int {
        val map = chunkFailureMap()
        val next = map.optInt(chunkName, 0) + 1
        map.put(chunkName, next)
        prefs.edit().putString(KEY_CHUNK_FAILURES, map.toString()).apply()
        return next
    }

    override fun clearChunkFailure(chunkName: String) {
        val map = chunkFailureMap()
        if (!map.has(chunkName)) return
        map.remove(chunkName)
        prefs.edit().putString(KEY_CHUNK_FAILURES, map.toString()).apply()
    }

    override fun chunkFailureCount(chunkName: String): Int = chunkFailureMap().optInt(chunkName, 0)

    override fun quarantinedChunks(): Set<String> =
        prefs.getStringSet(KEY_QUARANTINED_CHUNKS, emptySet()) ?: emptySet()

    override fun quarantineChunk(chunkName: String) {
        prefs.edit()
            .putStringSet(KEY_QUARANTINED_CHUNKS, quarantinedChunks() + chunkName)
            .apply()
    }

    override fun clearQuarantinedChunk(chunkName: String) {
        prefs.edit()
            .putStringSet(KEY_QUARANTINED_CHUNKS, quarantinedChunks() - chunkName)
            .apply()
    }

    /** 失败计数读取（损坏的存量 JSON 按空表兜底——计数错了大不了多试两次，不能炸同步） */
    private fun chunkFailureMap(): JSONObject {
        val raw = prefs.getString(KEY_CHUNK_FAILURES, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    /**
     * KDF 参数（三件套 + 盐，Base64 编码持久化）与口令校验值 KCV（架构 §1.6 密钥派生链）。
     * T-2 的 `crypto/SyncCrypto.kt` 把它们组装为 `KdfParams(mKiB = kdfMemoryKib, t = kdfIterations,
     * p = kdfParallelism, salt = kdfSalt)`；同口令设备经相同参数派生出同一把主密钥。
     */
    var kdfMemoryKib: Int
        get() = prefs.getInt(KEY_KDF_M_KIB, KDF_DEFAULT_MEMORY_KIB)
        set(value) {
            prefs.edit().putInt(KEY_KDF_M_KIB, value).apply()
        }

    var kdfIterations: Int
        get() = prefs.getInt(KEY_KDF_T, KDF_DEFAULT_ITERATIONS)
        set(value) {
            prefs.edit().putInt(KEY_KDF_T, value).apply()
        }

    var kdfParallelism: Int
        get() = prefs.getInt(KEY_KDF_P, KDF_DEFAULT_PARALLELISM)
        set(value) {
            prefs.edit().putInt(KEY_KDF_P, value).apply()
        }

    /** KDF 盐（16B）；null = 尚未设置口令 */
    var kdfSalt: ByteArray?
        get() = prefs.getString(KEY_KDF_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) {
            prefs.edit()
                .putString(KEY_KDF_SALT, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .apply()
        }

    /** 口令校验值 KCV = HMAC-SHA256(encKey, "SimpleLedger/v1/check")[0..16)；口令是否正确靠它比对 */
    var kcv: ByteArray?
        get() = prefs.getString(KEY_KCV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) {
            prefs.edit()
                .putString(KEY_KCV, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .apply()
        }

    /** 内容加密密钥 encKey（32B，T-4 派生密钥持久化裁量）；null = 未接入同步 */
    var encKey: ByteArray?
        get() = prefs.getString(KEY_ENC_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) {
            prefs.edit()
                .putString(KEY_ENC_KEY, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .apply()
        }

    /** 文件名假名密钥 nameKey（32B，T-4 派生密钥持久化裁量）；null = 未接入同步 */
    var nameKey: ByteArray?
        get() = prefs.getString(KEY_NAME_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) {
            prefs.edit()
                .putString(KEY_NAME_KEY, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .apply()
        }

    /** setupAccount 收尾：一次性持久化 KDF 参数 + KCV + 派生密钥 */
    override fun saveDerived(keys: SyncKeys, kdf: KdfParams) {
        prefs.edit()
            .putInt(KEY_KDF_M_KIB, kdf.mKiB)
            .putInt(KEY_KDF_T, kdf.t)
            .putInt(KEY_KDF_P, kdf.p)
            .putString(KEY_KDF_SALT, Base64.encodeToString(kdf.salt, Base64.NO_WRAP))
            .putString(KEY_KCV, Base64.encodeToString(keys.kcv, Base64.NO_WRAP))
            .putString(KEY_ENC_KEY, Base64.encodeToString(keys.encKey, Base64.NO_WRAP))
            .putString(KEY_NAME_KEY, Base64.encodeToString(keys.nameKey, Base64.NO_WRAP))
            .apply()
    }

    /** 取回派生密钥三件套；未接入同步返回 null */
    override fun loadKeys(): SyncKeys? {
        val enc = encKey ?: return null
        val name = nameKey ?: return null
        val check = kcv ?: return null
        return SyncKeys(encKey = enc, nameKey = name, kcv = check)
    }

    /** 取回 KDF 参数；盐缺失（未设置口令）返回 null */
    override fun kdfParams(): KdfParams? {
        val salt = kdfSalt ?: return null
        return KdfParams(mKiB = kdfMemoryKib, t = kdfIterations, p = kdfParallelism, salt = salt)
    }

    /** 同步成功收尾：盖时间戳、清错误（SyncEngine 调用） */
    override fun markSyncSuccess(atMillis: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_AT, atMillis).putString(KEY_LAST_ERROR, null).apply()
    }

    /** 同步失败收尾：只记错误枚举名，不弹任何 UI（S6 静默失败） */
    override fun markSyncError(errorName: String) {
        prefs.edit().putString(KEY_LAST_ERROR, errorName).apply()
    }

    /**
     * 重置同步（R-05）时清空本文件全部字段：本地账本零触碰，只作废同步侧状态。
     * WebDAV 凭证由 [SyncAccount] 另行管理，不在此处。
     */
    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** 计数是否属于当前月份 */
    private fun inCurrentMonth(): Boolean = monthlyKey == currentMonthKey()

    private fun currentMonthKey(): String = monthKey(Date())

    companion object {
        private const val PREFS_NAME = "simple_ledger_sync"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SELF_MEMBER_ID = "self_member_id"
        private const val KEY_WIFI_ONLY_PHOTOS = "wifi_only_photos"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_MONTHLY_KEY = "monthly_key"
        private const val KEY_MONTHLY_UP = "monthly_photo_up"
        private const val KEY_MONTHLY_DOWN = "monthly_photo_down"
        private const val KEY_KDF_M_KIB = "kdf_m_kib"
        private const val KEY_KDF_T = "kdf_t"
        private const val KEY_KDF_P = "kdf_p"
        private const val KEY_KDF_SALT = "kdf_salt"
        private const val KEY_KCV = "kcv"
        private const val KEY_ENC_KEY = "enc_key"
        private const val KEY_NAME_KEY = "name_key"
        private const val KEY_CHUNK_FAILURES = "chunk_failures"
        private const val KEY_QUARANTINED_CHUNKS = "quarantined_chunks"

        /** Argon2id 定案参数（架构 V1）：m = 64 MiB、t = 2、p = 1；参数随密文头存储、可调不破兼容 */
        const val KDF_DEFAULT_MEMORY_KIB = 65_536
        const val KDF_DEFAULT_ITERATIONS = 2
        const val KDF_DEFAULT_PARALLELISM = 1

        /**
         * 月键 = "yyyyMM"（R-19 跨月归零判据）。U-15：SimpleDateFormat 非线程安全，
         * 而 [currentMonthKey] 会被主线程（设置页 `monthlyPhotoUsage` 读数）与同步 IO
         * 线程（PHOTOS 阶段 `addPhotoTraffic` 记账）**并发调用**——共享单例实例可能把
         * internal calendar 写坏，产出的错键使 `inCurrentMonth` 恒 false、月流量读数永久
         * 归零，R-19 额度门静默失效。每次调用新建实例：调用频率极低（每次照片记账 /
         * 读数各一次），开销可忽略，换取无锁线程安全。
         */
        fun monthKey(at: Date): String =
            SimpleDateFormat("yyyyMM", Locale.US).format(at)
    }
}
