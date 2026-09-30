package com.simpleledger.app.sync

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.RemoteFileEntity
import com.simpleledger.app.data.local.entity.RemoteFileKind
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.data.local.entity.TrashResolved
import com.simpleledger.app.data.local.dao.RowWinnerProjection
import com.simpleledger.app.logic.OpTrimRules
import com.simpleledger.app.sync.account.AccountStore
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.RowStore
import com.simpleledger.app.sync.op.SyncOp
import com.simpleledger.app.sync.op.TxRunner
import com.simpleledger.app.sync.op.toModel
import com.simpleledger.app.sync.photo.NetworkStatus
import com.simpleledger.app.sync.photo.PhotoRefs
import com.simpleledger.app.sync.photo.PhotoStore
import com.simpleledger.app.sync.photo.PhotoSyncReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 同步子系统 JVM 单测共用夹具（OpMergeTest / SyncEngineIntegrationTest /
 * PhotoTransferTest / SyncManagerTest 共用，避免多文件重复实现）。
 *
 * 口径：假件只替掉 Room 存取 / Android 持久化 / 网络感知；合并语义、编排、
 * 加密传输全部走**生产实现**原样。
 */

/** 假件状态快照能力（FakeTx 整体回滚用） */
internal interface Snapshottable {
    fun snapshotState(): Any
    fun restoreState(state: Any)
}

/** 假事务：最外层快照，抛错整体恢复（对齐 Room withTransaction 单事务语义，R-12） */
internal class FakeTx(private val parts: List<Snapshottable>) : TxRunner {
    var rollbacks = 0
    private var depth = 0

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        if (depth > 0) {
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }
        val snapshots = parts.map { it.snapshotState() }
        depth = 1
        try {
            val result = block()
            depth = 0
            return result
        } catch (t: Throwable) {
            parts.forEachIndexed { index, part -> part.restoreState(snapshots[index]) }
            rollbacks++
            depth = 0
            throw t
        }
    }
}

/** SyncDao 内存实现（全部方法真实现，无占位） */
internal class FakeSyncDao : SyncDao, Snapshottable {
    val opLog = LinkedHashMap<String, SyncOpEntity>()
    val trashTable = LinkedHashMap<String, ConflictTrashEntity>()
    val memberTable = LinkedHashMap<String, MemberEntity>()
    val fileTable = LinkedHashMap<String, RemoteFileEntity>()

    override suspend fun upsertMember(member: MemberEntity) {
        memberTable[member.syncId] = member
    }

    override suspend fun getMember(syncId: String): MemberEntity? = memberTable[syncId]

    override suspend fun findMemberByName(name: String): MemberEntity? =
        memberTable.values.firstOrNull { it.name == name }

    override suspend fun listMembers(): List<MemberEntity> =
        memberTable.values.sortedWith(compareBy({ it.createdAt }, { it.syncId }))

    override fun observeMembers(): Flow<List<MemberEntity>> = flowOf(
        memberTable.values.sortedWith(compareBy({ it.createdAt }, { it.syncId })),
    )

    override suspend fun countMembers(): Int = memberTable.size

    override suspend fun insertOp(op: SyncOpEntity): Long {
        if (opLog.containsKey(op.opId)) return -1L
        opLog[op.opId] = op
        return opLog.size.toLong()
    }

    override suspend fun insertOps(ops: List<SyncOpEntity>) {
        ops.forEach { insertOp(it) }
    }

    override suspend fun getOp(opId: String): SyncOpEntity? = opLog[opId]

    override suspend fun countOp(opId: String): Int = if (opLog.containsKey(opId)) 1 else 0

    override suspend fun outbox(limit: Int): List<SyncOpEntity> =
        opLog.values.filter { !it.uploaded && it.origin == OpOrigin.LOCAL }
            .sortedWith(compareBy({ it.createdAt }, { it.opId }))
            .take(limit)

    override suspend fun countOutbox(): Int =
        opLog.values.count { !it.uploaded && it.origin == OpOrigin.LOCAL }

    // P0-2：可观察版同口径（sync-ui 加 DAO 方法时的接线；与 observeMembers 一样一次性快照即可，
    // 用到它的 UI 侧测试不依赖 Room 失效通知重发）
    override fun observeOutboxCount(): Flow<Int> = flowOf(
        opLog.values.count { !it.uploaded && it.origin == OpOrigin.LOCAL },
    )

    override suspend fun markUploaded(opIds: List<String>, chunkName: String) {
        opIds.forEach { id -> opLog[id]?.let { opLog[id] = it.copy(uploaded = true, chunkName = chunkName) } }
    }

    override suspend fun deferredOps(): List<SyncOpEntity> =
        opLog.values.filter { !it.applied }.sortedWith(compareBy({ it.createdAt }, { it.opId }))

    override suspend fun countDeferredOps(): Int = opLog.values.count { !it.applied }

    override suspend fun markApplied(opIds: List<String>) {
        opIds.forEach { id -> opLog[id]?.let { opLog[id] = it.copy(applied = true) } }
    }

    override suspend fun opsOfRow(rowKind: String, rowSyncId: String): List<SyncOpEntity> =
        opLog.values.filter { it.rowKind == rowKind && it.rowSyncId == rowSyncId }
            .sortedWith(compareBy({ it.seq }, { it.actorId }, { it.opId }))

    override suspend fun countOpsOfRow(rowKind: String, rowSyncId: String): Int =
        opLog.values.count { it.rowKind == rowKind && it.rowSyncId == rowSyncId }

    override suspend fun maxSeqOf(rowKind: String, rowSyncId: String): Long? =
        opLog.values.filter { it.rowKind == rowKind && it.rowSyncId == rowSyncId }
            .maxOfOrNull { it.seq }

    override suspend fun removePendingOps(opIds: List<String>): Int {
        var removed = 0
        opIds.forEach { id ->
            val op = opLog[id]
            if (op != null && !op.uploaded && op.origin == OpOrigin.LOCAL) {
                opLog.remove(id)
                removed++
            }
        }
        return removed
    }

    override suspend fun insertTrash(trash: ConflictTrashEntity): Long {
        if (trashTable.containsKey(trash.deleteOpId)) return -1L
        trashTable[trash.deleteOpId] = trash
        return trashTable.size.toLong()
    }

    override suspend fun updateTrash(trash: ConflictTrashEntity) {
        trashTable[trash.deleteOpId] = trash
    }

    override suspend fun getTrash(deleteOpId: String): ConflictTrashEntity? = trashTable[deleteOpId]

    override fun observeVisibleTrash(): Flow<List<ConflictTrashEntity>> = flowOf(listVisibleTrashSync())

    override suspend fun listVisibleTrash(): List<ConflictTrashEntity> = listVisibleTrashSync()

    override fun observeVisibleTrashCount(): Flow<Int> =
        flowOf(trashTable.values.count { it.resolved == TrashResolved.VISIBLE })

    override suspend fun countVisibleTrash(): Int =
        trashTable.values.count { it.resolved == TrashResolved.VISIBLE }

    override suspend fun resolveTrash(deleteOpId: String, resolved: Int) {
        trashTable[deleteOpId]?.let { trashTable[deleteOpId] = it.copy(resolved = resolved) }
    }

    override suspend fun deleteTrash(deleteOpId: String) {
        trashTable.remove(deleteOpId)
    }

    override suspend fun purgeTrashBefore(cutoffMillis: Long): Int {
        val doomed = trashTable.values.filter { it.deletedAt < cutoffMillis }.map { it.deleteOpId }
        doomed.forEach { trashTable.remove(it) }
        return doomed.size
    }

    /** 到期行清单（调用方提取 contentHash 做照片物理释放判据），内存件直接按 cutoff 过滤 */
    override suspend fun listTrashBefore(cutoffMillis: Long): List<ConflictTrashEntity> =
        trashTable.values.filter { it.deletedAt < cutoffMillis }.sortedBy { it.deleteOpId }

    override suspend fun listAllTrash(): List<ConflictTrashEntity> =
        trashTable.values.sortedBy { it.deleteOpId }

    /** A2 照片引用挂起计数：留底快照含 contentHash 子串即算引用（与 SQL LIKE 口径一致） */
    override suspend fun countVisibleTrashRefs(contentHash: String): Int =
        trashTable.values.count {
            it.resolved == TrashResolved.VISIBLE && it.snapshot.contains(contentHash)
        }

    override suspend fun clearTrash() {
        trashTable.clear()
    }

    override suspend fun upsertRemoteFile(file: RemoteFileEntity) {
        fileTable[file.remoteName] = file
    }

    override suspend fun getRemoteFile(remoteName: String): RemoteFileEntity? = fileTable[remoteName]

    override suspend fun remoteFilesAll(): List<RemoteFileEntity> =
        fileTable.values.sortedBy { it.remoteName }

    override suspend fun remoteFilesByKind(kind: String): List<RemoteFileEntity> =
        fileTable.values.filter { it.kind == kind }.sortedBy { it.remoteName }

    override suspend fun undownloadedChunks(): List<RemoteFileEntity> =
        fileTable.values.filter { it.kind == RemoteFileKind.OP_CHUNK && it.downloadedAt == 0L }
            .sortedBy { it.remoteName }

    override suspend fun markDownloaded(remoteName: String, at: Long) {
        fileTable[remoteName]?.let { fileTable[remoteName] = it.copy(downloadedAt = at) }
    }

    override suspend fun markFileUploaded(remoteName: String, etag: String?, size: Long, at: Long) {
        fileTable[remoteName]?.let {
            fileTable[remoteName] = it.copy(etag = etag, size = size, uploadedAt = at)
        }
    }

    override suspend fun deleteRemoteFile(remoteName: String) {
        fileTable.remove(remoteName)
    }

    override suspend fun clearRemoteFiles() {
        fileTable.clear()
    }

    override suspend fun clearOps() {
        opLog.clear()
    }

    override suspend fun countRowsWithSyncId(syncId: String): Int = 0

    // —— U-12 操作日志裁剪（内存实现，口径与 SyncDao SQL 逐条对齐）——

    override suspend fun trimCandidates(cutoffMillis: Long): List<SyncOpEntity> =
        opLog.values.filter {
            (it.uploaded || it.origin == "REMOTE") && it.applied &&
                it.createdAt < cutoffMillis && it.opType == "UPSERT" && it.rowKind != "TRASH"
        }.sortedWith(compareBy({ it.createdAt }, { it.opId }))

    // 与 SyncDao.latestUpsertPerRow 同口径（按 (seq, actorId, opId) 全序取最大）；
    // SQL 侧语义由 SyncDaoLatestUpsertSqlTest 用真实 SQLite + 生产建表语句钉死。
    override suspend fun latestUpsertPerRow(): List<RowWinnerProjection> =
        opLog.values.filter { it.opType == "UPSERT" }
            .groupBy { it.rowKind to it.rowSyncId }
            .map { (key, ops) ->
                val winner = ops.maxWith(compareBy({ it.seq }, { it.actorId }, { it.opId }))
                RowWinnerProjection(
                    rowKind = key.first,
                    rowSyncId = key.second,
                    winnerKey = OpTrimRules.winnerKey(winner.seq, winner.actorId, winner.opId),
                )
            }

    override suspend fun deleteOpsByIds(opIds: List<String>): Int {
        var removed = 0
        opIds.forEach { if (opLog.remove(it) != null) removed++ }
        return removed
    }

    private fun listVisibleTrashSync(): List<ConflictTrashEntity> =
        trashTable.values.filter { it.resolved == TrashResolved.VISIBLE }
            .sortedWith(compareByDescending<ConflictTrashEntity> { it.deletedAt }.thenBy { it.deleteOpId })

    override fun snapshotState(): Any = listOf(
        LinkedHashMap(opLog), LinkedHashMap(trashTable), LinkedHashMap(memberTable), LinkedHashMap(fileTable),
    )

    @Suppress("UNCHECKED_CAST")
    override fun restoreState(state: Any) {
        val parts = state as List<LinkedHashMap<String, Any>>
        opLog.clear(); opLog.putAll(parts[0] as LinkedHashMap<String, SyncOpEntity>)
        trashTable.clear(); trashTable.putAll(parts[1] as LinkedHashMap<String, ConflictTrashEntity>)
        memberTable.clear(); memberTable.putAll(parts[2] as LinkedHashMap<String, MemberEntity>)
        fileTable.clear(); fileTable.putAll(parts[3] as LinkedHashMap<String, RemoteFileEntity>)
    }
}

/** 行投影内存实现：按 syncId 幂等 upsert；引用三分判定与 RoomRowStore 同口径 */
internal class FakeRowStore(private val dao: FakeSyncDao) : RowStore, Snapshottable {
    data class FakeRow(val kind: RowKind, val syncId: String, val versionSeq: Long, val payload: JSONObject)

    val rows = LinkedHashMap<Pair<RowKind, String>, FakeRow>()
    val settings = LinkedHashMap<String, String>()
    private var nextId = 1L
    val localIds = LinkedHashMap<Pair<RowKind, String>, Long>()

    override suspend fun putWinner(rowKind: RowKind, rowSyncId: String, winner: SyncOp): Boolean {
        // 先在副本上做引用三分归一（与 RoomRowStore 同口径）：
        // 解析到 → 原引用；到过且已死 → 0 占位（CATEGORY 降级全局 = 去掉键）；从未到 → 挂起。
        val p = JSONObject(winner.payload.toString())
        when (rowKind) {
            RowKind.CATEGORY -> {
                if (p.has("sectionSyncId")) {
                    when (refState(p.getString("sectionSyncId"), RowKind.SECTION)) {
                        RefState.NEVER_ARRIVED -> return false
                        RefState.DEAD_PLACEHOLDER -> p.remove("sectionSyncId") // 降级全局
                        RefState.RESOLVED -> Unit
                    }
                }
            }
            RowKind.ENTRY -> {
                if (!normalizeRef(p, "categorySyncId", RowKind.CATEGORY)) return false
                if (!normalizeRef(p, "sectionSyncId", RowKind.SECTION)) return false
            }
            RowKind.IMAGE -> {
                if (!normalizeRef(p, "entrySyncId", RowKind.ENTRY)) return false
            }
            RowKind.SETTING -> settings[p.getString("key")] = p.optString("value", "")
            else -> Unit
        }
        val key = rowKind to rowSyncId
        rows[key] = FakeRow(rowKind, rowSyncId, winner.seq, p)
        localIds.putIfAbsent(key, nextId++)
        return true
    }

    /** 引用归一：真未到 → false（挂起）；到过已死 → 写 "0" 占位；正常 → 不动 */
    private suspend fun normalizeRef(p: JSONObject, field: String, kind: RowKind): Boolean =
        when (refState(p.getString(field), kind)) {
            RefState.NEVER_ARRIVED -> false
            RefState.DEAD_PLACEHOLDER -> {
                p.put(field, DEAD_REF_PLACEHOLDER)
                true
            }
            RefState.RESOLVED -> true
        }

    override suspend fun removeRow(rowKind: RowKind, rowSyncId: String) {
        rows.remove(rowKind to rowSyncId)
        localIds.remove(rowKind to rowSyncId)
        if (rowKind == RowKind.ENTRY) {
            val doomed = rows.values
                .filter { it.kind == RowKind.IMAGE && it.payload.optString("entrySyncId") == rowSyncId }
                .map { it.kind to it.syncId }
            doomed.forEach { rows.remove(it); localIds.remove(it) }
        }
    }

    fun exists(kind: RowKind, syncId: String): Boolean = rows.containsKey(kind to syncId)

    /** 引用三分：RESOLVED / DEAD_PLACEHOLDER（到过且已死）/ NEVER_ARRIVED（真挂起） */
    private suspend fun refState(syncId: String, kind: RowKind): RefState = when {
        exists(kind, syncId) -> RefState.RESOLVED
        dao.countOpsOfRow(kind.value, syncId) > 0 -> RefState.DEAD_PLACEHOLDER
        else -> RefState.NEVER_ARRIVED
    }

    /** 孤儿引用清单（R-12 演练断言用；"0" 占位不算孤儿，无删除场景下应恒为空） */
    suspend fun orphanReferences(): List<String> {
        val orphans = mutableListOf<String>()
        for (row in rows.values) {
            when (row.kind) {
                RowKind.CATEGORY -> if (row.payload.has("sectionSyncId")) {
                    val ref = row.payload.getString("sectionSyncId")
                    if (ref != DEAD_REF_PLACEHOLDER && !exists(RowKind.SECTION, ref)) {
                        orphans += "${row.syncId}→section:$ref"
                    }
                }
                RowKind.ENTRY -> {
                    val cat = row.payload.getString("categorySyncId")
                    val sec = row.payload.getString("sectionSyncId")
                    if (cat != DEAD_REF_PLACEHOLDER && !exists(RowKind.CATEGORY, cat)) {
                        orphans += "${row.syncId}→category:$cat"
                    }
                    if (sec != DEAD_REF_PLACEHOLDER && !exists(RowKind.SECTION, sec)) {
                        orphans += "${row.syncId}→section:$sec"
                    }
                }
                RowKind.IMAGE -> {
                    val entry = row.payload.getString("entrySyncId")
                    if (entry != DEAD_REF_PLACEHOLDER && !exists(RowKind.ENTRY, entry)) {
                        orphans += "${row.syncId}→entry:$entry"
                    }
                }
                else -> Unit
            }
        }
        return orphans
    }

    override fun snapshotState(): Any = listOf(
        LinkedHashMap(rows), LinkedHashMap(settings), LinkedHashMap(localIds), nextId,
    )

    @Suppress("UNCHECKED_CAST")
    override fun restoreState(state: Any) {
        val parts = state as List<Any>
        rows.clear(); rows.putAll(parts[0] as LinkedHashMap<Pair<RowKind, String>, FakeRow>)
        settings.clear(); settings.putAll(parts[1] as LinkedHashMap<String, String>)
        localIds.clear(); localIds.putAll(parts[2] as LinkedHashMap<Pair<RowKind, String>, Long>)
    }

    private enum class RefState { RESOLVED, DEAD_PLACEHOLDER, NEVER_ARRIVED }

    companion object {
        /** 到过已死的占位引用值（与 RoomRowStore 的 0 占位同口径，OpMergeTest 断言用） */
        const val DEAD_REF_PLACEHOLDER = "0"
    }
}

/** 故障注入：第 N 次行写抛错（R-12 演练制造「半途失败」） */
internal class FaultyRowStore(
    private val delegate: RowStore,
    private val failAtWrite: Int,
) : RowStore {
    private var writes = 0

    override suspend fun putWinner(rowKind: RowKind, rowSyncId: String, winner: SyncOp): Boolean {
        if (++writes == failAtWrite) throw IllegalStateException("注入故障：第 $failAtWrite 次行写")
        return delegate.putWinner(rowKind, rowSyncId, winner)
    }

    override suspend fun removeRow(rowKind: RowKind, rowSyncId: String) {
        if (++writes == failAtWrite) throw IllegalStateException("注入故障：第 $failAtWrite 次行写")
        delegate.removeRow(rowKind, rowSyncId)
    }
}

/**
 * 一台设备 = 独立操作日志 + 行投影 + 事务边界（真实 OpRecorder + OpApplier + OpMerge 全链路）。
 * OpMergeTest（收敛矩阵）与 SyncEngineIntegrationTest（双实例全链收敛）共用。
 */
internal class SyncTestDevice(val name: String) {
    val dao = FakeSyncDao()
    val store = FakeRowStore(dao)
    val tx = FakeTx(listOf(dao, store))
    val recorder = OpRecorder(dao, { name }, { "member-$name" })
    val applier = OpApplier(dao, store, tx)
    private var tick = 1_000L

    /** 种子行：本地预置、无操作（对齐 v5 种子/存量行，versionSeq = 0） */
    fun seedRow(kind: RowKind, syncId: String, payload: JSONObject, versionSeq: Long = 0L) {
        val key = kind to syncId
        store.rows[key] = FakeRowStore.FakeRow(kind, syncId, versionSeq, payload)
        store.localIds[key] = 100L + store.localIds.size
    }

    /** 本地业务写（真实实现 = LedgerRepository 写行 + OpRecorder 记操作，同事务） */
    suspend fun localUpsert(kind: RowKind, syncId: String, payload: JSONObject, baseSeq: Long?): SyncOp {
        val seq = (baseSeq ?: 0L) + 1
        val opId = recorder.onUpsert(kind, syncId, seq, baseSeq, payload)
        val op = SyncOp(
            opId, kind, syncId, OpType.UPSERT, name, "member-$name", seq, baseSeq, payload, tick++,
        )
        store.putWinner(kind, syncId, op)
        return op
    }

    suspend fun localDelete(kind: RowKind, syncId: String, baseSeq: Long, snapshot: JSONObject): SyncOp {
        val payload = OpCodec.withDeletedAt(snapshot, tick)
        val opId = recorder.onDelete(kind, syncId, baseSeq, payload)
        val op = SyncOp(
            opId, kind, syncId, OpType.DELETE, name, "member-$name", 0, baseSeq, payload, tick++,
        )
        store.removeRow(kind, syncId)
        return op
    }

    fun allOps(): List<SyncOp> = dao.opLog.values.map { it.toModel() }

    suspend fun receiveFrom(other: SyncTestDevice) = applier.applyRemote(other.allOps())

    fun state(): String {
        val rowsText = store.rows.entries
            .sortedBy { "${it.key.first.value}:${it.key.second}" }
            .joinToString("\n") { (key, row) ->
                "${key.first.value}/${key.second} v${row.versionSeq} ${canonicalJson(row.payload)}"
            }
        val trashText = dao.trashTable.entries.sortedBy { it.key }.joinToString("\n") { (id, t) ->
            "$id ${t.rowKind}/${t.rowSyncId} kind=${t.kind} conflict=${t.conflict} " +
                "actor=${t.conflictActorId} resolved=${t.resolved} ${canonicalJson(JSONObject(t.snapshot))}"
        }
        val settingsText = store.settings.toSortedMap().toString()
        return "$rowsText\n--\n$trashText\n--\n$settingsText"
    }
}

/** SyncStore 内存实现（含派生密钥持久化三方法） */
internal class FakeSyncStore : SyncStore {
    override val deviceId: String = "fake-device-000"
    override var selfMemberId: String? = null
    override var wifiOnlyPhotos: Boolean = true
    override var lastSyncAt: Long = 0L
    override var lastError: String? = null
    var savedKeys: SyncKeys? = null
    var savedKdf: KdfParams? = null
    var photoUp: Long = 0L
    var photoDown: Long = 0L

    override fun markSyncSuccess(atMillis: Long) {
        lastSyncAt = atMillis
        lastError = null
    }

    override fun markSyncError(errorName: String) {
        lastError = errorName
    }

    override fun saveDerived(keys: SyncKeys, kdf: KdfParams) {
        savedKeys = keys
        savedKdf = kdf
    }

    override fun loadKeys(): SyncKeys? = savedKeys

    override fun kdfParams(): KdfParams? = savedKdf

    override fun addPhotoTraffic(upBytes: Long, downBytes: Long): Pair<Long, Long> {
        photoUp += upBytes.coerceAtLeast(0L)
        photoDown += downBytes.coerceAtLeast(0L)
        return photoUp to photoDown
    }

    override fun monthlyPhotoUsage(): Pair<Long, Long> = photoUp to photoDown

    // —— U-7 损坏分片隔离（内存实现）——
    private val chunkFailures = LinkedHashMap<String, Int>()
    private val quarantined = LinkedHashSet<String>()

    override fun recordChunkFailure(chunkName: String): Int {
        val next = (chunkFailures[chunkName] ?: 0) + 1
        chunkFailures[chunkName] = next
        return next
    }

    override fun clearChunkFailure(chunkName: String) {
        chunkFailures.remove(chunkName)
    }

    override fun chunkFailureCount(chunkName: String): Int = chunkFailures[chunkName] ?: 0

    override fun quarantinedChunks(): Set<String> = quarantined.toSet()

    override fun quarantineChunk(chunkName: String) {
        quarantined.add(chunkName)
    }

    override fun clearQuarantinedChunk(chunkName: String) {
        quarantined.remove(chunkName)
    }

    // —— U-7 照片侧隔离（内存实现，口径与分片侧同构）——
    private val photoFailures = LinkedHashMap<String, Int>()
    private val quarantinedPhotos = LinkedHashSet<String>()

    override fun recordPhotoFailure(photoName: String): Int {
        val next = (photoFailures[photoName] ?: 0) + 1
        photoFailures[photoName] = next
        return next
    }

    override fun clearPhotoFailure(photoName: String) {
        photoFailures.remove(photoName)
    }

    override fun photoFailureCount(photoName: String): Int = photoFailures[photoName] ?: 0

    override fun quarantinedPhotos(): Set<String> = quarantinedPhotos.toSet()

    override fun quarantinePhoto(photoName: String) {
        quarantinedPhotos.add(photoName)
    }

    override fun clearQuarantinedPhoto(photoName: String) {
        quarantinedPhotos.remove(photoName)
    }

    override fun clearAll() {
        selfMemberId = null
        wifiOnlyPhotos = true
        lastSyncAt = 0L
        lastError = null
        savedKeys = null
        savedKdf = null
        photoUp = 0L
        photoDown = 0L
        chunkFailures.clear()
        quarantined.clear()
        photoFailures.clear()
        quarantinedPhotos.clear()
    }
}

/** AccountStore 内存实现 */
internal class FakeAccountStore(private var cred: WebDavCred? = null) : AccountStore {
    override fun load(): WebDavCred? = cred
    override fun save(cred: WebDavCred) {
        this.cred = cred
    }

    override fun clear() {
        cred = null
    }
}

/** PhotoStore 内存实现 */
internal class FakePhotoStore : PhotoStore {
    val files = LinkedHashMap<String, ByteArray>()

    override fun exists(contentHash: String): Boolean = files.containsKey(contentHash)
    override fun readBytes(contentHash: String): ByteArray? = files[contentHash]
    override fun writeBytes(contentHash: String, bytes: ByteArray) {
        files[contentHash] = bytes
    }

    override fun pathForHash(contentHash: String): String = "/fake/photos/$contentHash"
}

/**
 * PhotoRefs 内存实现。[gate] 非空时 contentHashes() 先挂起等待——单飞/并发测试用。
 */
internal class FakePhotoRefs(
    var hashes: List<String> = emptyList(),
    var gate: CompletableDeferred<Unit>? = null,
) : PhotoRefs {
    override suspend fun contentHashes(): List<String> {
        gate?.await()
        return hashes
    }
}

/** NetworkStatus 可拨杆假件（Wi-Fi 门控矩阵用） */
internal class FakeNetworkStatus(
    var online: Boolean = true,
    var wifi: Boolean = true,
) : NetworkStatus {
    override fun isOnline(): Boolean = online
    override fun isWifi(): Boolean = wifi
}

/** InitialExportSource 内存清单（RoomInitialExporter 的确定性导出测试用） */
internal class FakeExportSource(
    var members: List<MemberEntity> = emptyList(),
    var sections: List<SectionEntity> = emptyList(),
    var categories: List<CategoryEntity> = emptyList(),
    var entries: List<EntryEntity> = emptyList(),
    var images: List<EntryImageEntity> = emptyList(),
) : InitialExportSource {
    override suspend fun members(): List<MemberEntity> = members
    override suspend fun sections(): List<SectionEntity> = sections
    override suspend fun categories(): List<CategoryEntity> = categories
    override suspend fun entries(): List<EntryEntity> = entries
    override suspend fun images(): List<EntryImageEntity> = images
}

/** InitialExporter 假件（固定导出计数；编排测试用） */
internal class FakeInitialExporter(private val count: Int = 0) : InitialExporter {
    var calls = 0
    override suspend fun export(): Int {
        calls++
        return count
    }
}

/** SyncRunner 假件（SyncManager 去抖/单飞编排测试用） */
internal class FakeSyncRunner : SyncRunner {
    override val state: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.Never)
    var calls = 0
    var nextOutcome: SyncOutcome = SyncOutcome(success = true)

    override suspend fun syncOnce(trigger: SyncTrigger): SyncOutcome {
        calls++
        return nextOutcome
    }

    override fun clearState() {
        state.value = SyncState.Never
    }
}

/** 空跑 PhotoSyncReport 引用（断言便捷） */
internal val EMPTY_PHOTO_REPORT = PhotoSyncReport.EMPTY

/**
 * JSON 键序归一（org.json 的 toString 不保证键序，收敛比较必须先规范化）。
 * 顶层函数：Device 等嵌套类也要用（嵌套类无法调外部类的成员函数）。
 */
internal fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> {
        val keys = mutableListOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        keys.sorted().joinToString(",", "{", "}") { key ->
            "\"$key\":${canonicalJson(value.get(key))}"
        }
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    null -> "null"
    else -> JSONObject.quote(value.toString())
}
