package com.simpleledger.app.sync

import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.RemoteFileEntity
import com.simpleledger.app.data.local.entity.RemoteFileKind
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.sync.dav.DavErrors
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.dav.fileName
import com.simpleledger.app.sync.crypto.SyncCryptoException
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.SyncOp
import com.simpleledger.app.sync.op.TxRunner
import com.simpleledger.app.sync.op.UnknownOpEnumException
import com.simpleledger.app.sync.op.toModel
import com.simpleledger.app.sync.photo.PhotoSyncReport
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import com.simpleledger.app.util.SlLog

/**
 * 同步执行端口（`SyncManager` 的依赖；JVM 单测可注入假件测去抖/单飞编排）。
 */
interface SyncRunner {
    val state: StateFlow<SyncState>

    /** 一轮同步（S6：永不向调用方抛异常，失败只回 [SyncOutcome.error]） */
    suspend fun syncOnce(trigger: SyncTrigger): SyncOutcome

    /** R-05 重置同步后把角标打回「从未同步」 */
    fun clearState() {}
}

/**
 * 一轮同步编排（§4.1）：PUSH_OPS → PULL_OPS → MERGE → PHOTOS。
 *
 * 断点口径（R-12/R-07）：
 * - PUSH：outbox 分片上传成功才 `markUploaded`；中途失败下轮续传（分片幂等，
 *   同操作重复上传靠 opId `INSERT OR IGNORE` 折叠）；
 * - PULL：新分片**全部下载并 MERGE 成功后**才记台账 `downloadedAt`——
 *   任何一步失败都不留「已下载未应用」窗口，下轮整片重拉（幂等重放收敛一致）；
 * - MERGE：`OpApplier.applyRemote` 单事务，失败整体回滚（R-12）；
 * - PULL 遇**确定性损坏**的分片（密文解析/GCM 失败、明文非合法 JSON）：连续 3 次后隔离
 *   跳过，不拖死整轮（U-7）；手动「立即同步」重试一次作逃生口，状态详情透出隔离数。
 *
 * 与 §3.7 签名的差异（缺口补齐，T-2/3 先例）：
 * - `remote: WebDavRemote` 改为 `remoteProvider: () -> WebDavRemote?`——setupAccount /
 *   换凭证后重建远端；null = 未配置同步（返回 `SyncOutcome.skip(NOT_CONFIGURED)`）；
 * - 移除未使用的 `db/ops` 依赖：写路径埋点在 `LedgerRepository`（T-3），引擎只消费 outbox；
 * - `photo` 的远端实例由本类传入 `PhotoTransfer.syncPendingPhotos(remote)`。
 *
 * 单飞：内部 [Mutex] `tryLock`——同一时刻只跑一轮，占用中的触发直接 SKIPPED
 * （去抖 60s 在 `SyncManager.requestSync`）。
 */
class SyncEngine(
    private val syncDao: SyncDao,
    private val applier: OpApplier,
    private val photo: PhotoTransfer,
    private val store: SyncStore,
    private val remoteProvider: () -> WebDavRemote?,
) : SyncRunner {

    private val gate = Mutex()

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    override suspend fun syncOnce(trigger: SyncTrigger): SyncOutcome {
        if (!gate.tryLock()) return SyncOutcome.skip(SkipReason.IN_FLIGHT) // 单飞：已有同步在跑
        try {
            val remote = remoteProvider() ?: return SyncOutcome.skip(SkipReason.NOT_CONFIGURED)
            var pushedOps = 0
            var pulledChunks = 0
            var appliedOps = 0
            var deferredOps = 0

            // ------------------------------------------------------------ PUSH_OPS
            _state.value = SyncState.Syncing(SyncPhase.PUSH_OPS)
            while (true) {
                val batch = syncDao.outbox(OPS_PER_CHUNK)
                if (batch.isEmpty()) break
                val (chunk, approxBytes) = cutChunk(batch)
                val name = remote.uploadChunk(
                    OpCodec.encodeChunk(chunk.map { it.toModel() }).toByteArray(Charsets.UTF_8)
                )
                val now = System.currentTimeMillis()
                syncDao.markUploaded(chunk.map { it.opId }, name)
                // 自己的分片无需回拉：台账直接记为已下载（重复投递靠 opId 幂等折叠）
                syncDao.upsertRemoteFile(
                    RemoteFileEntity(
                        remoteName = name,
                        kind = RemoteFileKind.OP_CHUNK,
                        etag = null,
                        size = approxBytes,
                        downloadedAt = now,
                        uploadedAt = now,
                    )
                )
                pushedOps += chunk.size
            }

            // ------------------------------------------------------------ PULL_OPS
            _state.value = SyncState.Syncing(SyncPhase.PULL_OPS)
            val pending = mutableListOf<SyncOp>()
            val fetched = mutableListOf<RemoteFileEntity>()
            // U-7 损坏分片隔离：连续解码失败达阈值的分片本轮跳过（防坏分片拖死整轮、
            // 同步永久卡死）。MANUAL 触发不跳——「立即同步」对被隔离分片重试一次（逃生口）：
            // 先解除隔离，仍损坏则按计数当场重新隔离，其余分片不受影响。
            val quarantined = store.quarantinedChunks()
            val skipSet = if (trigger == SyncTrigger.MANUAL) emptySet() else quarantined
            for (res in remote.listRemote()) {
                val name = res.fileName()
                if (!name.endsWith(CHUNK_SUFFIX)) continue // 只认操作分片；照片/元文件另有归属
                if (syncDao.getRemoteFile(name) != null) continue // 台账差集（增量游标）
                if (name in skipSet) continue // U-7：隔离跳过；内容缺失经状态详情透出（非静默）
                if (name in quarantined) store.clearQuarantinedChunk(name) // 手动重试：先解除再试
                val plain = try {
                    remote.downloadChunk(name)
                } catch (t: Throwable) {
                    // U-7：确定性内容损坏 → 计数/隔离后继续拉其余分片；
                    // 瞬态错误（网络 / HTTP / 口令）照旧整轮失败（下轮重拉，语义不变）
                    if (!t.isChunkCorruption()) throw t
                    onChunkCorrupt(name, t)
                    continue
                }
                val ops = try {
                    OpCodec.decodeChunk(String(plain, Charsets.UTF_8))
                } catch (t: Throwable) {
                    // 同上：密文解开了但明文不是合法分片 JSON，同属确定性损坏（U-7）
                    if (!t.isChunkCorruption()) throw t
                    onChunkCorrupt(name, t)
                    continue
                }
                store.clearChunkFailure(name) // U-7：成功即清零（截断下载等偶发损坏不积累）
                pending += ops
                fetched += RemoteFileEntity(
                    remoteName = name,
                    kind = RemoteFileKind.OP_CHUNK,
                    etag = res.etag,
                    size = res.size ?: plain.size.toLong(),
                    downloadedAt = 0,
                    uploadedAt = 0,
                )
                pulledChunks++
            }

            // ------------------------------------------------------------ MERGE
            _state.value = SyncState.Syncing(SyncPhase.MERGE)
            if (pending.isNotEmpty()) {
                val result = applier.applyRemote(pending) // 单事务（R-12）
                appliedOps += result.applied
                deferredOps += result.deferred
            }
            val retry = applier.retryDeferred() // §3.5-6：下轮开始/本轮末尾重试挂起集
            appliedOps += retry.applied
            deferredOps += retry.deferred
            // 回放成功才推进台账（失败不记 → 下轮重拉，杜绝「已下载未应用」丢操作窗口）
            val mergedAt = System.currentTimeMillis()
            fetched.forEach { syncDao.upsertRemoteFile(it.copy(downloadedAt = mergedAt)) }

            // ------------------------------------------------------------ PHOTOS
            _state.value = SyncState.Syncing(SyncPhase.PHOTOS)
            // U-7 照片侧对称：坏照片（下载/解密/哈希不符）在 PhotoTransfer 内计数隔离，
            // 不再让单张坏照片把整轮拖成永久 Failed；MANUAL「立即同步」是逃生口
            val photoReport: PhotoSyncReport = photo.syncPendingPhotos(
                remote,
                retryQuarantined = trigger == SyncTrigger.MANUAL,
            )

            store.markSyncSuccess(System.currentTimeMillis())
            _state.value = SyncState.Idle
            return SyncOutcome(
                success = true,
                pushedOps = pushedOps,
                pulledChunks = pulledChunks,
                appliedOps = appliedOps,
                deferredOps = deferredOps,
                photo = photoReport,
                quarantinedChunks = store.quarantinedChunks().size,
                quarantinedPhotos = store.quarantinedPhotos().size,
            )
        } catch (t: Throwable) {
            // S6 静默收口：任何失败都不外抛，只落角标 + lastError；
            // v1.4.2 补日志（println 口径同 deriveTimed：JVM 单测不炸、Android 落 Logcat）
            SlLog.d("SyncEngine", "syncOnce($trigger) failed: ${t.message ?: t.javaClass.name}", t)
            val error = SyncError.fromName(DavErrors.toSyncErrorName(t))
            store.markSyncError(error.name)
            _state.value = SyncState.Failed(error, System.currentTimeMillis())
            return SyncOutcome(success = false, error = error)
        } finally {
            gate.unlock()
        }
    }

    override fun clearState() {
        _state.value = SyncState.Never
    }

    // ------------------------------------------------------------------ 内部

    /**
     * U-7：损坏计数 +1，达 [CHUNK_QUARANTINE_THRESHOLD] 进隔离名单。
     * 坏分片只是载体、其内容从未被应用过——隔离的代价是该分片内容暂缺且
     * 状态详情可见（非静默），收益是其余分片照常收敛、同步不再被拖死。
     */
    private fun onChunkCorrupt(name: String, t: Throwable) {
        val failures = store.recordChunkFailure(name)
        SlLog.d("SyncEngine", "chunk $name corrupt (consecutive=$failures): ${t.message ?: t.javaClass.name}")
        if (failures >= CHUNK_QUARANTINE_THRESHOLD) store.quarantineChunk(name)
    }

    /**
     * U-7 隔离判据：只认**确定性**内容损坏（密文解析/GCM 认证失败、明文非合法分片 JSON、
     * 解压失败、未知 rowKind/opType 枚举值）。网络中断、HTTP 错、口令错都是瞬态或全局性
     * 错误，必须照旧整轮失败——尤其 BadPassword 隔离哪个分片都无意义（整个账本都解不开）。
     *
     * 判据补全（本轮）：GCM 认证保证随机损坏在解析前就被判 Corrupted，但**版本偏斜**
     * （新版 App 写入新 RowKind/OpType，§7-5 字段演进路径）或编码器 bug 产出的分片能
     * 顺利通过解密与 gzip，死在枚举解析（[UnknownOpEnumException]）或解压（已由
     * SyncCrypto.open 归为 BadFormat）上——这类确定性损坏原先穿透判据，整轮永久失败、
     * 永不计数隔离，正是 U-7 承诺消灭的「坏分片拖死同步」。
     */
    private fun Throwable.isChunkCorruption(): Boolean =
        this is DavException.Corrupted ||
            this is SyncCryptoException ||
            this is JSONException ||
            this is UnknownOpEnumException

    /** 初始角标由持久化状态推出（冷启动不闪 Never：上次失败 → 直接 Failed） */
    private fun initialState(): SyncState {
        val errorName = store.lastError
        return when {
            errorName != null -> SyncState.Failed(SyncError.fromName(errorName), store.lastSyncAt)
            store.lastSyncAt > 0L -> SyncState.Idle
            else -> SyncState.Never
        }
    }

    /** 分片切割：≤ [OPS_PER_CHUNK] 条且（估算）≤ [CHUNK_MAX_CHARS] 字符（§7-5） */
    private fun cutChunk(batch: List<SyncOpEntity>): Pair<List<SyncOpEntity>, Long> {
        val chosen = ArrayList<SyncOpEntity>(batch.size)
        var approx = 0L
        for (op in batch) {
            val size = op.payload.length + OP_OVERHEAD_CHARS
            if (chosen.isNotEmpty() && approx + size > CHUNK_MAX_CHARS) break
            chosen.add(op)
            approx += size
        }
        return chosen to approx
    }

    companion object {
        /** 单分片操作条数上限（§7-5） */
        const val OPS_PER_CHUNK = 128

        /** 单分片明文体积上限（§7-5，字符近似字节——JSON UTF-8 中文会更长，留裕量） */
        const val CHUNK_MAX_CHARS = 240 * 1024

        /** 单操作固定字段的体积估算余量 */
        private const val OP_OVERHEAD_CHARS = 192

        /** 操作分片文件后缀（§7-6：随机 32hex + ".op"） */
        const val CHUNK_SUFFIX = ".op"

        /**
         * U-7 分片隔离阈值：连续损坏次数达此值才隔离。计 3 次而非首败即隔离——
         * 下载截断等偶发损坏与真损坏同形（GCM 认证失败），给重试留余地。
         */
        const val CHUNK_QUARANTINE_THRESHOLD = 3
    }
}

/**
 * 存量导出端口（setupAccount 一次性调用；JVM 测试注入假件）。
 */
interface InitialExporter {
    /**
     * 为「尚无任何操作」的存量行补记确定性 UPSERT（见 [RoomInitialExporter] 的幂等口径），
     * 返回本次导出的操作数。
     */
    suspend fun export(): Int
}

/** 存量行读取端口（生产 = [RoomInitialExportSource]；JVM 测试 = 内存清单） */
interface InitialExportSource {
    suspend fun members(): List<MemberEntity>
    suspend fun sections(): List<SectionEntity>
    suspend fun categories(): List<CategoryEntity>
    suspend fun entries(): List<EntryEntity>
    suspend fun images(): List<EntryImageEntity>
}

/** [InitialExportSource] 的 Room 实现 */
class RoomInitialExportSource(private val db: AppDatabase) : InitialExportSource {
    override suspend fun members(): List<MemberEntity> = db.syncDao().listMembers()
    override suspend fun sections(): List<SectionEntity> = db.sectionDao().getAll()
    override suspend fun categories(): List<CategoryEntity> = db.categoryDao().getAll()
    override suspend fun entries(): List<EntryEntity> = db.entryDao().allEntries()
    override suspend fun images(): List<EntryImageEntity> = db.entryDao().allImages()
}

/**
 * 首次接入存量导出（§4.2「存量导出」步骤）：把 v4 迁移 / 种子行等**从未记过操作**的行，
 * 以「存在声明」形式补记一条 UPSERT 操作，让其余设备能拉到本机存量。
 *
 * **确定性 opId（本轮关键裁量，幂等 + 收敛）**：
 * `opId = "export-<rowSyncId>-<sha256(规范化快照JSON)[0..16)>"`。
 * - 两机同内容导出 → 同 opId → `INSERT OR IGNORE` 折叠为一条（无伪 OVERWRITE 留底）；
 * - 异内容（v4 迁移期各自改过的行，U-5）→ 异 opId → LWW + OVERWRITE 双留（收敛且不丢）。
 *
 * 版本口径：`seq = 行 versionSeq`（迁移存量行可为 0，即「版本 0 的存在声明」）、
 * `baseSeq = null`。后续编辑（`baseSeq = versionSeq`）在数字模型上**观察过**该声明
 * （`observed(0, 0) = true`），串行覆盖不留底，与 U-3 契约一致。
 *
 * 幂等口径：行已有任何操作（`countOpsOfRow > 0`）即跳过——正常写路径（T-3 埋点）
 * 产生的行不需要导出；重复调用 export() 零副作用。
 */
class RoomInitialExporter(
    private val source: InitialExportSource,
    private val syncDao: SyncDao,
    private val tx: TxRunner,
    private val actorIdOf: () -> String,
    private val memberIdOf: () -> String?,
) : InitialExporter {

    override suspend fun export(): Int = tx.runInTransaction {
        val members = source.members()
        val sections = source.sections()
        val categories = source.categories()
        val entries = source.entries()
        val images = source.images()
        val sectionSyncIds = HashMap<Long, String>()
        sections.forEach { sectionSyncIds[it.id] = it.syncId }
        val categorySyncIds = HashMap<Long, String>()
        categories.forEach { categorySyncIds[it.id] = it.syncId }
        val entrySyncIds = HashMap<Long, String>()
        entries.forEach { entrySyncIds[it.id] = it.syncId }

        var created = 0

        // MEMBER 定义置顶导出（不在 §3.5-5 分层序内，无引用依赖）：resetSync 保留 members
        // 却清空操作账，re-setup 后唯有这里的补记（与 claimMember 复用路径的补记互为双保险）
        // 能把成员定义带回操作日志——缺了它，其他设备（新装/重装）永远拉不到成员，
        // entries.memberId 悬空，成员标签/按人统计（R-18/R-20）静默失效。
        for (row in members) {
            created += emit(
                rowKind = RowKind.MEMBER,
                rowSyncId = row.syncId,
                seq = row.versionSeq,
                updatedAt = row.updatedAt,
                snapshot = OpCodec.memberSnapshot(
                    name = row.name,
                    hidden = row.hidden,
                    createdAt = row.createdAt,
                ),
            )
        }

        // 分层序导出（§3.5-5：SECTION→CATEGORY→ENTRY→IMAGE，尊重引用序）
        for (row in sections) {
            created += emit(
                rowKind = RowKind.SECTION,
                rowSyncId = row.syncId,
                seq = row.versionSeq,
                updatedAt = row.updatedAt,
                snapshot = OpCodec.sectionSnapshot(
                    name = row.name,
                    iconId = row.iconId,
                    note = row.note,
                    budgetCents = row.budgetCents,
                    colorIndex = row.colorIndex,
                    sortOrder = row.sortOrder,
                    createdAt = row.createdAt,
                ),
            )
        }
        for (row in categories) {
            created += emit(
                rowKind = RowKind.CATEGORY,
                rowSyncId = row.syncId,
                seq = row.versionSeq,
                updatedAt = row.updatedAt,
                snapshot = OpCodec.categorySnapshot(
                    name = row.name,
                    iconId = row.iconId,
                    type = row.type,
                    sectionSyncId = row.sectionId?.let { sectionSyncIds[it] },
                    sortOrder = row.sortOrder,
                ),
            )
        }
        for (row in entries) {
            created += emit(
                rowKind = RowKind.ENTRY,
                rowSyncId = row.syncId,
                seq = row.versionSeq,
                updatedAt = row.updatedAt,
                snapshot = OpCodec.entrySnapshot(
                    type = row.type,
                    amountCents = row.amountCents,
                    categorySyncId = categorySyncIds[row.categoryId] ?: "",
                    sectionSyncId = sectionSyncIds[row.sectionId] ?: "",
                    entryTime = row.entryTime,
                    note = row.note,
                    reconciled = row.reconciled,
                    reimburseState = row.reimburseState,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    memberSyncId = row.memberId,
                ),
            )
        }
        for (row in images) {
            created += emit(
                rowKind = RowKind.IMAGE,
                rowSyncId = row.syncId,
                seq = row.versionSeq,
                updatedAt = row.updatedAt,
                snapshot = OpCodec.imageSnapshot(
                    entrySyncId = entrySyncIds[row.entryId] ?: "",
                    contentHash = row.contentHash,
                    sortOrder = row.sortOrder,
                ),
            )
        }
        created
    }

    /** 单行导出（调用方保证在事务内）；返回 0/1 */
    private suspend fun emit(
        rowKind: RowKind,
        rowSyncId: String,
        seq: Long,
        updatedAt: Long,
        snapshot: JSONObject,
    ): Int {
        if (rowSyncId.isEmpty()) return 0
        if (syncDao.countOpsOfRow(rowKind.value, rowSyncId) > 0) return 0 // 已有历史，跳过
        syncDao.insertOp(
            SyncOpEntity(
                opId = exportOpId(rowSyncId, snapshot),
                rowKind = rowKind.value,
                rowSyncId = rowSyncId,
                opType = OpType.UPSERT.value,
                actorId = actorIdOf(),
                memberId = memberIdOf(),
                seq = seq,
                baseSeq = null, // 「存在声明」：不观察任何历史（U-3 口径）
                payload = snapshot.toString(),
                origin = OpOrigin.LOCAL,
                applied = true,
                uploaded = false,
                chunkName = null,
                createdAt = updatedAt,
            )
        )
        return 1
    }

    companion object {
        /**
         * 确定性 opId：同 (行, 同内容) 跨设备/跨时间恒等（幂等折叠的根）。
         * 规范化 = 键序排序的 JSON 文本（org.json 的 toString 不保证键序）。
         */
        fun exportOpId(rowSyncId: String, snapshot: JSONObject): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJsonForExport(snapshot).toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
            return "export-$rowSyncId-$hex"
        }
    }
}

/** 键序归一的 JSON 文本（导出确定性 opId 用；与 OpMergeTest 的 canonicalJson 同口径） */
private fun canonicalJsonForExport(value: Any?): String = when (value) {
    is JSONObject -> {
        val keys = mutableListOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        keys.sorted().joinToString(",", "{", "}") { key ->
            "\"$key\":${canonicalJsonForExport(value.get(key))}"
        }
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
        canonicalJsonForExport(value.get(it))
    }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    null -> "null"
    else -> JSONObject.quote(value.toString())
}
