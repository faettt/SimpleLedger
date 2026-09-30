package com.simpleledger.app.sync

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.logic.OpTrimRules
import com.simpleledger.app.logic.TrashAggregation
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 连通性测试结果（R-01：区分 网络错 / 凭证错 / 表单非法） */
sealed class TestResult {
    data object Ok : TestResult()
    data class InvalidCred(val issue: WebDavCredIssue) : TestResult()

    /**
     * 连通性失败。[detail] = 可定位摘要（`DavErrors.detailOf`：Http 档给
     * 「HTTP 403 PROPFIND /dav/」，其余档给异常 message / 类名）——各档都带，
     * 没 detail 用户只能盲试凭证（v1.4.2 排障口）。
     */
    data class Failed(val reason: SyncError, val detail: String? = null) : TestResult()
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

    /**
     * 其余失败（网络 / 凭证 / 超额 / 损坏…）。
     * [detail] 诊断信息（失败步骤 + 异常摘要，如「写元文件: WebDAV PUT … → HTTP 405」）——
     * UNKNOWN 档 UI 透出，让「同步出错」可定位（v1.4.2 排障口）。
     */
    data class Failed(val reason: SyncError, val detail: String? = null) : SetupResult()
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
    /**
     * U-17：R-21 到期清理的照片实体文件释放端口（contentHash 集合 → 按 [PhotoRetention]
     * 判据物理删文件）。生产装配（AppContainer，本批次范围外）应传与
     * `OpApplier.photoRelease` 同判据的实现（业务引用 + 剩余可见留底引用双归零才删）；
     * 默认空实现（JVM 测试注入记录器）。⚠️ 生产未接线前，到期清理只删留底行、
     * 不释放照片文件（孤儿文件照旧累积）。
     */
    private val photoRelease: suspend (Set<String>) -> Unit = {},
) {

    /** 角标状态（U-4 四态）；T-5 `SyncStatusBadge` 订阅 */
    val state: StateFlow<SyncState> get() = engine.state

    /** 自动触发去抖基准时刻（内存即可：进程重启后冷启动触发本来就该放行） */
    private val lastAutoRequestAt = AtomicLong(0L)

    /** AFTER_WRITE 合并窗口是否已排程（true = 窗口内已有一轮在路上，后续写并入该轮） */
    private val writeSyncArmed = AtomicBoolean(false)

    /**
     * 异步触发一轮同步。三档语义互不干扰：
     * - [SyncTrigger.MANUAL]：不去抖，立即执行（屏上「立即同步」要求即时反馈）；
     * - [SyncTrigger.AFTER_WRITE]（P0-1）：**独立合并窗口** [WRITE_DEBOUNCE_MILLIS]——
     *   首个写请求排程一轮 3s 后的同步，窗口内其余写并入该轮（不重排、不多发）；
     * - 其余自动三路（冷启动 / 回前台 / 30min 周期）：[DEBOUNCE_MILLIS] 60s 去抖。
     *
     * 未配置同步（无凭证/无派生密钥）一律静默返回（S6），引擎侧另有
     * `skipReason = NOT_CONFIGURED` 的兜底表达。
     */
    fun requestSync(trigger: SyncTrigger) {
        if (!isConfigured()) return
        if (trigger == SyncTrigger.AFTER_WRITE) {
            scheduleAfterWrite()
            return
        }
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

    /**
     * 写后触发的合并窗口（P0-1）。
     *
     * 为什么是「排程 + 延迟执行」而不是像自动触发那样立即发起：
     * `OpRecorder` 的写入口在**业务事务内**被调用（`LedgerRepository` 的
     * `db.withTransaction`），立即同步会让引擎在别的事务连接上读 outbox——WAL 下读到的是
     * 提交前快照，**刚记的这笔根本不在 outbox 里**，白跑一轮后要等下一个触发（最快也是
     * 回前台，否则 30min 周期）才补传，正是 P0-1 要消灭的「记完账不上传」。窗口 3s 覆盖
     * 事务提交 + 连续记账（一笔多行：行 + 操作 + 照片）合并，一次上传全带走。
     *
     * 合并口径：窗口内只保留一轮（[writeSyncArmed]）；窗口一到就**先解除再跑**——这轮在跑
     * 期间到达的新写可以排下一轮，不会被吞掉（引擎单飞会把并发的重复触发挡成
     * `skipReason = IN_FLIGHT`，不重复上传）。与 60s 自动窗口各用各的时间戳：写后触发不
     * 消耗也不受自动窗口压制。
     */
    private fun scheduleAfterWrite() {
        if (!writeSyncArmed.compareAndSet(false, true)) return
        val job = scope.launch {
            delay(WRITE_DEBOUNCE_MILLIS)
            writeSyncArmed.set(false)
            runCatching { engine.syncOnce(SyncTrigger.AFTER_WRITE) }
        }
        // 兜底：作用域被取消（进程收尾 / 测试提前结束）时也要把窗口标志归位，
        // 否则写路径从此永久不再排程（取消发生在 delay 之前时 finally 不会执行）。
        job.invokeOnCompletion { writeSyncArmed.set(false) }
    }

    /** 手动「立即同步」/ Worker 周期同步；挂起至完成返回结果（S6：永不抛异常） */
    suspend fun syncNow(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncOutcome {
        val outcome = runCatching { engine.syncOnce(trigger) }
            .getOrElse { SyncOutcome(success = false, error = SyncError.fromName(DavErrors.toSyncErrorName(it))) }
        // R-21 自动清理：同步收尾顺手清 90 天前的删除留底（失败不影响同步结果；
        // 与 ConflictTrashViewModel.init 的进页清理互为双挂——不进回收站页也会到期清理）。
        // U-17：必须与回收站页**三步同口径**——先 listTrashBefore 取到期行、purge 后按
        // IMAGE 快照的 contentHash 调 [photoRelease] 释放实体文件。留底是 A2 照片挂起
        // 期间其文件的唯一引用方，30 分钟周期同步几乎总先于用户进回收站页执行：只 purge
        // 不释放，这批 contentHash 从此没有任何代码路径再覆盖，照片文件在 filesDir/images
        // 永久累积为孤儿（纯磁盘泄漏，无数据丢失）。
        runCatching {
            val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MILLIS
            val expiring = syncDao.listTrashBefore(cutoff)
            syncDao.purgeTrashBefore(cutoff)
            if (expiring.isNotEmpty()) {
                photoRelease(TrashAggregation.imageHashesOf(expiring))
            }
        }
        // U-12 操作日志裁剪：与回收站清理并列的成功收尾（失败不影响同步结果）；
        // 失败 / 跳过轮不裁——裁剪只建立在「本轮账已对齐」的前提上
        if (outcome.success && !outcome.skipped) {
            runCatching {
                val trimmed = trimOps()
                if (trimmed > 0) println("[SyncManager] trimOps removed $trimmed old ops")
            }
        }
        return outcome
    }

    /**
     * U-12 操作日志裁剪（syncNow 成功收尾）：分批删除「非 outbox（已上传 / REMOTE）+
     * 已应用 + 超过保留期（对齐 R-21 的 90 天）」的历史 UPSERT。正确性论证见
     * `logic/OpTrimRules`（每行保留全序最大 UPSERT + 全部 DELETE，引用判定与未来合并
     * 收敛不受影响）。每轮最多 [TRIM_MAX_BATCHES] 批，防大库首轮裁剪拖长单轮同步。
     *
     * 为什么「读每行胜者 + 分批删候选」必须包在**一个事务**里：单飞锁只护
     * engine.syncOnce，本收尾在其释放后执行——若允许另一轮 syncOnce 的 OpApplier
     * 并发落账，一条 createdAt 已超保留期的**迟到 REMOTE 操作**可能恰好成为某行新的
     * 全序胜者，它在旧胜者快照里没有保底，会被下一批候选误删；而其所在云端分片已按
     * `sync_remote_files` 游标标记已下载、**再不会被重拉** ⇒ 与未裁设备永久分歧。
     * 单事务对其他写者原子（SQLite 单写者串行）：并发轮要么整体先落账（胜者快照已含它），
     * 要么整体后落账（本轮不可见、下轮重算胜者后保底），竞态即消除。
     */
    private suspend fun trimOps(): Int {
        return tx.runInTransaction {
            val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MILLIS
            val winners = syncDao.latestUpsertPerRow().map {
                OpTrimRules.RowWinner(it.rowKind, it.rowSyncId, it.winnerKey)
            }
            var removedTotal = 0
            var batches = 0
            while (batches < TRIM_MAX_BATCHES) {
                val doomed = OpTrimRules.trimmableOpIds(
                    candidates = syncDao.trimCandidates(cutoff).map {
                        OpTrimRules.OpRow(
                            opId = it.opId,
                            rowKind = it.rowKind,
                            rowSyncId = it.rowSyncId,
                            opType = it.opType,
                            seq = it.seq,
                            actorId = it.actorId,
                            origin = it.origin,
                            uploaded = it.uploaded,
                            applied = it.applied,
                            createdAt = it.createdAt,
                        )
                    },
                    winners = winners,
                    cutoffMillis = cutoff,
                )
                if (doomed.isEmpty()) break
                val batch = doomed.take(TRIM_BATCH_SIZE).toList()
                removedTotal += syncDao.deleteOpsByIds(batch)
                batches++
                if (batch.size < TRIM_BATCH_SIZE) break // 不足一批 = 已清空
            }
            removedTotal
        }
    }

    /** R-01 连通性测试：PROPFIND WebDAV 根（只读探针，不建目录不写数据） */
    suspend fun testConnection(cred: WebDavCred): TestResult {
        cred.validate()?.let { return TestResult.InvalidCred(it) }
        return try {
            davFactory(cred).propfindDepth1("")
            TestResult.Ok
        } catch (t: Throwable) {
            TestResult.Failed(SyncError.fromName(DavErrors.toSyncErrorName(t)), DavErrors.detailOf(t))
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
            // 凭证/拒访两档尤其需要 detail（401/407 → AUTH；403 → ACCESS_DENIED，
            // 后者成因有额度/目录/二次验证/频控多种）：
            // 接入失败页「同步出错」透出定位串，用户不必盲试口令（T2）
            is TestResult.Failed -> return SetupResult.Failed(probe.reason, probe.detail)
            is TestResult.InvalidCred -> return SetupResult.InvalidCred(probe.issue)
            TestResult.Ok -> Unit
        }

        val keys: SyncKeys
        val kdf: KdfParams
        var step = "探云端元文件"
        try {
            // ① 无口令发现（placeholder keys 只碰 nameKey 直取 + 56B 头兜底探测）
            val header = remoteFactory(cred, PLACEHOLDER_KEYS, PLACEHOLDER_KDF).peekMetaHeader()
            if (header != null) {
                // ② 有 meta：从明文头取 kdf/salt → 派生 → KCV 比对（R-04：下载任何数据前失败）
                kdf = header.kdf
                step = "派生密钥"
                keys = deriveTimed(password, kdf)
                val expectKcv = header.kcv ?: return SetupResult.Failed(SyncError.CORRUPTED, "meta 头缺少 KCV")
                if (!crypto.checkPassword(keys, expectKcv)) return SetupResult.BadPassword
            } else {
                // ③ 无 meta：本机建账（首写 If-None-Match:*；412 竞争 → 内部重读校验口令）
                step = "生成派生参数"
                kdf = KdfParams(
                    mKiB = SyncPrefs.KDF_DEFAULT_MEMORY_KIB,
                    t = SyncPrefs.KDF_DEFAULT_ITERATIONS,
                    p = SyncPrefs.KDF_DEFAULT_PARALLELISM,
                    salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) },
                )
                keys = deriveTimed(password, kdf)
                val remote = remoteFactory(cred, keys, kdf)
                step = "建协议目录"
                remote.ensureLayout()
                step = "写元文件"
                try {
                    remote.writeMetaOnce(
                        MetaBody(bookId = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
                    )
                } catch (e: DavException.BadPassword) {
                    return SetupResult.BadPassword // 首写 412 竞争且他机口令不一致
                }
            }
        } catch (t: Throwable) {
            return failedAt(step, t)
        }

        // 持久化凭证 + 派生密钥（自动同步需本地持钥，§7-10 取舍；忘口令 = resetSync 清掉）
        account.save(cred)
        store.saveDerived(keys, kdf)

        // 成员认领（U-2）→ 存量导出 → 首轮双向同步（S5：无「选哪边」）
        val memberSyncId = claimMember(memberName)
            ?: return SetupResult.Failed(SyncError.UNKNOWN, "成员名为空")
        val exportedOps = try {
            exporter.export()
        } catch (t: Throwable) {
            return failedAt("存量导出", t)
        }
        val outcome = syncNow(SyncTrigger.MANUAL)
        return SetupResult.Success(memberSyncId, exportedOps, outcome)
    }

    /**
     * 诊断收口（v1.4.2 排障口）：失败步骤 + 异常摘要进 [SetupResult.Failed.detail]
     * （UNKNOWN 档 UI 透出），并 println 落 Logcat（口径同 deriveTimed：JVM 单测不炸）。
     */
    private fun failedAt(step: String, t: Throwable): SetupResult.Failed {
        val detail = "$step: ${t.message ?: t.javaClass.name}"
        println("[SyncManager] setup failed at $detail")
        t.printStackTrace()
        return SetupResult.Failed(SyncError.fromName(DavErrors.toSyncErrorName(t)), detail)
    }

    /**
     * 成员认领（U-2）：全书唯一名——同名复用既有成员（resetSync 后 re-setup 也走此路），
     * 首次认领生成 32hex UUID 并记 MEMBER UPSERT（其余设备经操作日志认识该成员）；
     * 复用路径同样补记 MEMBER UPSERT（操作账可能已被 resetSync 清空，见方法内注释）。
     * 返回成员 syncId；名为空返回 null。
     */
    suspend fun claimMember(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val syncId = tx.runInTransaction {
            val existing = syncDao.findMemberByName(trimmed)
            if (existing != null) {
                // resetSync 保留 members 却清空操作账：re-setup 走到这里的复用路径若不补记，
                // 该成员定义从此进不了操作日志，其他设备（新装/重装）永远认识不了它——
                // entries.memberId 悬空，成员标签/按人统计（R-18/R-20）静默失效。
                // 与 first-claim 同事务口径补记「本机认识这个成员」（与存量导出的 MEMBER
                // 补记互为双保险）；补记与既有 op 同 seq 同内容，LWW 任选其一收敛状态一致。
                recorder.onUpsert(
                    rowKind = RowKind.MEMBER,
                    rowSyncId = existing.syncId,
                    seq = existing.versionSeq,
                    baseSeq = null,
                    snapshot = OpCodec.memberSnapshot(existing.name, existing.hidden, existing.createdAt),
                )
                return@runInTransaction existing.syncId
            }
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
     * U-7 状态详情取数：当前被隔离的损坏分片数（0 = 无）。
     * 隔离不挡其余同步（角标仍是成功口径），内容缺失在状态详情明说——
     * 「立即同步」会重试被隔离分片，重置同步清空记录。
     */
    fun quarantinedChunkCount(): Int = store.quarantinedChunks().size

    /**
     * U-7 照片侧对称的状态详情取数：当前被隔离的坏照片数（0 = 无）。
     * 隔离不挡其余照片同步（角标仍是成功口径），缺失在状态详情明说——
     * 「立即同步」会重试被隔离照片，重置同步清空记录。
     */
    fun quarantinedPhotoCount(): Int = store.quarantinedPhotos().size

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

        /**
         * 写后触发（[SyncTrigger.AFTER_WRITE]）合并窗口（P0-1：3s）。
         * 取值依据：① 必须跨过业务事务提交（否则引擎读不到刚记的操作）；
         * ② 连续记账（一笔多行 + 照片引用）合并成一轮上传；③ 短到用户感知仍是「刚记完就同步」。
         * 与 [DEBOUNCE_MILLIS] 各自独立计时，互不消耗。
         */
        const val WRITE_DEBOUNCE_MILLIS = 3_000L

        /** setupAccount 默认成员名（T-5 可让用户改） */
        const val DEFAULT_MEMBER_NAME = "我"

        /** 回收站留底保留期：90 天（R-21 自动清理判据，双挂在 syncNow 收尾 + 回收站 VM） */
        const val TRASH_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

        /** U-12 操作日志裁剪：单批删除条数 */
        const val TRIM_BATCH_SIZE = 500

        /** U-12 操作日志裁剪：单轮 syncNow 最多批数（防大库首轮拖长同步） */
        const val TRIM_MAX_BATCHES = 20

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
