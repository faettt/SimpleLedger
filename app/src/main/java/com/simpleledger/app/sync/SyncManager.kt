package com.simpleledger.app.sync

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.sync.account.AccountStore
import com.simpleledger.app.sync.account.SyncPrefs
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.DavErrors
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.MetaBody
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.dav.fileName
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.TxRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 连通性测试结果（R-01：区分 网络错 / 凭证错 / 表单非法） */
sealed class TestResult {
    data object Ok : TestResult()
    data class InvalidCred(val issue: WebDavCredIssue) : TestResult()
    data class Failed(val reason: SyncError) : TestResult()
}

/**
 * 首次接入结果（§4.2）。
 * [BadPassword] 单独一档：R-04 要求「口令不一致」在**下载任何数据前**失败，UI 有专属文案。
 */
sealed class SetupResult {
    /** 成功；[outcome] = 接入后首轮双向同步的结果 */
    data class Success(
        val memberSyncId: String,
        val exportedOps: Int,
        val outcome: SyncOutcome,
    ) : SetupResult()

    /** 口令不一致（KCV 校验失败，R-04） */
    data object BadPassword : SetupResult()

    /** 凭证表单非法（UI 映射具体字段提示） */
    data class InvalidCred(val issue: WebDavCredIssue) : SetupResult()

    /** 其余失败（网络 / 凭证 / 超额 / 损坏…） */
    data class Failed(val reason: SyncError) : SetupResult()
}

/**
 * App 级同步门面（§3.7，`AppContainer` 装配）：四路触发的去抖/单飞入口 +
 * 首次接入（setupAccount）+ 重置同步（resetSync）+ 成员认领（U-2）。
 *
 * 与 §3.7 签名的差异（缺口补齐，T-2/3 先例）：
 * - `engine` 依赖端口化为 [SyncRunner]、`prefs/account` 端口化为 [SyncStore]/[AccountStore]
 *   （JVM 测试注入假件测去抖/单飞/接入流程，无 Android Context）；
 * - 增补 `syncDao/recorder/exporter/tx`：成员认领与存量导出需要事务与操作埋点；
 * - 增补 `davFactory/remoteFactory`：testConnection 探根目录、setupAccount 的
 *   「无口令发现」（placeholder keys + peekMetaHeader）与 resetSync 删云端
 *   （只需 WebDAV 凭证、无需同步口令）各需不同形态的远端；
 * - `syncNow(trigger)` 增加默认参 [SyncTrigger.MANUAL]（Worker 传 PERIODIC）。
 *
 * setupAccount 流程（§4.2 + peekMetaHeader 定案）：
 * testConnection（PROPFIND 根；401→AUTH / 超时→NETWORK）→ peekMetaHeader
 * （placeholder keys 探 56B 头：① 本机假名 ② 列目录取非 .op 候选认 purpose=META）
 * → 有 meta：从头取 kdf/salt → deriveKeys → KCV 不符即 [SetupResult.BadPassword]
 * （**R-04：下载任何数据前失败**）→ 无 meta：writeMetaOnce(If-None-Match:*)
 * （412 竞争→内部重读走「有 meta」路径，口令不符同样 BadPassword）
 * → 成员认领 → 存量导出 → 首轮 syncOnce（双向合并取并集，无「选哪边」，S5）。
 */
class SyncManager(
    private val engine: SyncRunner,
    private val store: SyncStore,
    private val account: AccountStore,
    private val crypto: SyncCrypto,
    private val syncDao: SyncDao,
    private val recorder: OpRecorder,
    private val exporter: InitialExporter,
    private val tx: TxRunner,
    private val davFactory: (WebDavCred) -> MiniWebDavClient,
    private val remoteFactory: (WebDavCred, SyncKeys, KdfParams) -> WebDavRemote,
    private val scope: CoroutineScope,
) {

    /** 角标状态（U-4 四态）；T-5 `SyncStatusBadge` 订阅 */
    val state: StateFlow<SyncState> get() = engine.state

    /** 自动触发去抖基准时刻（内存即可：进程重启后冷启动触发本来就该放行） */
    private val lastAutoRequestAt = AtomicLong(0L)

    /**
     * 异步触发一轮同步（COLD_START / FOREGROUND / PERIODIC）。
     * 去抖 [DEBOUNCE_MILLIS] 60s（自动触发）+ 单飞（引擎内 Mutex tryLock）；
     * 未配置同步（无凭证/无派生密钥）直接静默返回（S6）。
     */
    fun requestSync(trigger: SyncTrigger) {
        if (!isConfigured()) return
        if (trigger != SyncTrigger.MANUAL) {
            val now = System.currentTimeMillis()
            val last = lastAutoRequestAt.get()
            if (now - last < DEBOUNCE_MILLIS) return
            if (!lastAutoRequestAt.compareAndSet(last, now)) return
        }
        scope.launch {
            runCatching { engine.syncOnce(trigger) } // 双保险：即便引擎意外抛错也不外溢（S6）
        }
    }

    /** 手动「立即同步」/ Worker 周期同步；挂起至完成返回结果（S6：永不抛异常） */
    suspend fun syncNow(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncOutcome {
        val outcome = runCatching { engine.syncOnce(trigger) }
            .getOrElse { SyncOutcome(success = false, error = SyncError.fromName(DavErrors.toSyncErrorName(it))) }
        // R-21 自动清理：同步收尾顺手清 90 天前的删除留底（失败不影响同步结果；
        // 与 ConflictTrashViewModel.init 的进页清理互为双挂——不进回收站页也会到期清理）
        runCatching {
            syncDao.purgeTrashBefore(System.currentTimeMillis() - TRASH_RETENTION_MILLIS)
        }
        return outcome
    }

    /** R-01 连通性测试：PROPFIND WebDAV 根（只读探针，不建目录不写数据） */
    suspend fun testConnection(cred: WebDavCred): TestResult {
        cred.validate()?.let { return TestResult.InvalidCred(it) }
        return try {
            davFactory(cred).propfindDepth1("")
            TestResult.Ok
        } catch (t: Throwable) {
            TestResult.Failed(SyncError.fromName(DavErrors.toSyncErrorName(t)))
        }
    }

    /**
     * 首次接入（含存量导出），流程见类注释。[memberName] 为本机认领的成员名
     * （全书唯一；同名视为复用既有成员，U-2/U-10）。
     */
    suspend fun setupAccount(
        cred: WebDavCred,
        password: CharArray,
        memberName: String = DEFAULT_MEMBER_NAME,
    ): SetupResult {
        cred.validate()?.let { return SetupResult.InvalidCred(it) }
        when (val probe = testConnection(cred)) {
            is TestResult.Failed -> return SetupResult.Failed(probe.reason)
            is TestResult.InvalidCred -> return SetupResult.InvalidCred(probe.issue)
            TestResult.Ok -> Unit
        }

        val keys: SyncKeys
        val kdf: KdfParams
        try {
            // ① 无口令发现（placeholder keys 只碰 nameKey 直取 + 56B 头兜底探测）
            val header = remoteFactory(cred, PLACEHOLDER_KEYS, PLACEHOLDER_KDF).peekMetaHeader()
            if (header != null) {
                // ② 有 meta：从明文头取 kdf/salt → 派生 → KCV 比对（R-04：下载任何数据前失败）
                kdf = header.kdf
                keys = deriveTimed(password, kdf)
                val expectKcv = header.kcv ?: return SetupResult.Failed(SyncError.CORRUPTED)
                if (!crypto.checkPassword(keys, expectKcv)) return SetupResult.BadPassword
            } else {
                // ③ 无 meta：本机建账（首写 If-None-Match:*；412 竞争 → 内部重读校验口令）
                kdf = KdfParams(
                    mKiB = SyncPrefs.KDF_DEFAULT_MEMORY_KIB,
                    t = SyncPrefs.KDF_DEFAULT_ITERATIONS,
                    p = SyncPrefs.KDF_DEFAULT_PARALLELISM,
                    salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) },
                )
                keys = deriveTimed(password, kdf)
                val remote = remoteFactory(cred, keys, kdf)
                remote.ensureLayout()
                try {
                    remote.writeMetaOnce(
                        MetaBody(bookId = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
                    )
                } catch (e: DavException.BadPassword) {
                    return SetupResult.BadPassword // 首写 412 竞争且他机口令不一致
                }
            }
        } catch (t: Throwable) {
            return SetupResult.Failed(SyncError.fromName(DavErrors.toSyncErrorName(t)))
        }

        // 持久化凭证 + 派生密钥（自动同步需本地持钥，§7-10 取舍；忘口令 = resetSync 清掉）
        account.save(cred)
        store.saveDerived(keys, kdf)

        // 成员认领（U-2）→ 存量导出 → 首轮双向同步（S5：无「选哪边」）
        val memberSyncId = claimMember(memberName)
            ?: return SetupResult.Failed(SyncError.UNKNOWN)
        val exportedOps = try {
            exporter.export()
        } catch (t: Throwable) {
            return SetupResult.Failed(SyncError.fromName(DavErrors.toSyncErrorName(t)))
        }
        val outcome = syncNow(SyncTrigger.MANUAL)
        return SetupResult.Success(memberSyncId, exportedOps, outcome)
    }

    /**
     * 成员认领（U-2）：全书唯一名——同名复用既有成员（resetSync 后 re-setup 也走此路），
     * 首次认领生成 32hex UUID 并记 MEMBER UPSERT（其余设备经操作日志认识该成员）。
     * 返回成员 syncId；名为空返回 null。
     */
    suspend fun claimMember(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val syncId = tx.runInTransaction {
            val existing = syncDao.findMemberByName(trimmed)
            if (existing != null) return@runInTransaction existing.syncId
            val now = System.currentTimeMillis()
            val member = MemberEntity(
                syncId = newSyncId32(),
                name = trimmed,
                hidden = false,
                createdAt = now,
                updatedAt = now,
                versionSeq = 1,
            )
            syncDao.upsertMember(member)
            recorder.onUpsert(
                rowKind = RowKind.MEMBER,
                rowSyncId = member.syncId,
                seq = 1,
                baseSeq = null,
                snapshot = OpCodec.memberSnapshot(trimmed, hidden = false, createdAt = now),
            )
            member.syncId
        }
        store.selfMemberId = syncId
        return syncId
    }

    /**
     * 重置同步（R-05）：作废云端重建，本地账本零触碰。
     *
     * 1. 尽力删云端文件（列目录逐删；只需 WebDAV 凭证、**不需要同步口令**，失败不阻断）；
     * 2. 清 `sync_ops` / `sync_trash` / `sync_remote_files` + `SyncPrefs.clearAll()`
     *    （含派生密钥——忘口令的唯一出路）；
     * 3. members **保留**（re-setup 时 `findMemberByName` 复用，历史成员标签不丢）；
     * 4. 角标打回「从未同步」。
     */
    suspend fun resetSync(): Result<Unit> = runCatching {
        val cred = account.load()
        if (cred != null) {
            runCatching {
                val dav = davFactory(cred)
                dav.propfindDepth1("${WebDavRemote.DIR}/")
                    .filter { !it.isDir }
                    .forEach { res -> runCatching { dav.delete("${WebDavRemote.DIR}/${res.fileName()}") } }
            }
        }
        tx.runInTransaction {
            syncDao.clearOps()
            syncDao.clearTrash()
            syncDao.clearRemoteFiles()
        }
        store.clearAll()
        engine.clearState()
    }

    /** 是否已完成同步配置（凭证 + 派生密钥齐备） */
    fun isConfigured(): Boolean = account.load() != null && store.loadKeys() != null

    /**
     * U-1：Argon2id 派生并记录耗时（验收要求留档）。
     * 用 println 而非 android.util.Log —— JVM 单测里 Log 会 not mocked，
     * println 在 Android 落 Logcat、在 JVM 无副作用。
     */
    private fun deriveTimed(password: CharArray, kdf: KdfParams): SyncKeys {
        val start = System.currentTimeMillis()
        val keys = crypto.deriveKeys(password, kdf)
        println("Argon2id derive ${System.currentTimeMillis() - start}ms (m=${kdf.mKiB}KiB t=${kdf.t} p=${kdf.p})")
        return keys
    }

    companion object {
        /** 自动触发去抖窗口（V3 定案：60s） */
        const val DEBOUNCE_MILLIS = 60_000L

        /** setupAccount 默认成员名（T-5 可让用户改） */
        const val DEFAULT_MEMBER_NAME = "我"

        /** 回收站留底保留期：90 天（R-21 自动清理判据，双挂在 syncNow 收尾 + 回收站 VM） */
        const val TRASH_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

        private const val SALT_BYTES = 16

        /**
         * 「无口令发现」用的占位密钥：peekMetaHeader 只会拿 nameKey 试算直取名
         * （猜不中就走 56B 头兜底探测），不参与任何解密，无所谓保密。
         */
        private val PLACEHOLDER_KEYS = SyncKeys(
            encKey = ByteArray(32),
            nameKey = ByteArray(32),
            kcv = ByteArray(16),
        )

        private val PLACEHOLDER_KDF = KdfParams(
            mKiB = SyncPrefs.KDF_DEFAULT_MEMORY_KIB,
            t = SyncPrefs.KDF_DEFAULT_ITERATIONS,
            p = SyncPrefs.KDF_DEFAULT_PARALLELISM,
            salt = ByteArray(SALT_BYTES),
        )

        /** 新行成员 syncId：UUID 32 字符小写（与 LedgerRepository.newSyncId 同口径） */
        fun newSyncId32(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
