package com.simpleledger.app.sync

import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.SyncOp
import com.simpleledger.app.sync.op.toModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * App 级同步门面（`SyncManager`）JVM 实测：R-01 连通性归类 / §4.2 setupAccount 全流程 /
 * R-04 口令错「下载任何数据前失败」/ R-05 resetSync / V3 去抖 / U-2 成员认领 /
 * 存量导出确定性 opId（RoomInitialExporter 真实现）。
 *
 * 引擎与账户层注入假件（FakeSyncRunner/FakeSyncStore/FakeAccountStore），
 * 云端走 MiniDavServer 真实 HTTP——setupAccount 的「无口令发现」（peekMetaHeader）
 * 与 resetSync 删云端全链与生产同构。
 */
class SyncManagerTest {

    private lateinit var server: MiniDavServer
    private lateinit var workRoot: File
    private val crypto = SyncCrypto()
    private lateinit var keys: SyncKeys
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 5 })
    private val password = "同步口令".toCharArray()

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-manager-test-${System.nanoTime()}")
        workRoot.mkdirs()
        keys = crypto.deriveKeys(password, kdf)
    }

    @After
    fun tearDown() {
        server.stop()
        workRoot.deleteRecursively()
    }

    // ------------------------------------------------------------ R-01：连通性归类

    @Test
    fun testConnection_ok() = runBlocking {
        val fx = fixture()
        assertEquals(TestResult.Ok, fx.manager.testConnection(cred()))
    }

    @Test
    fun testConnection_badCredentialIsAuthError() = runBlocking {
        server.requireAuth = MiniWebDavClient.basicCredentials("user", "app-pass")
        val fx = fixture()
        val result = fx.manager.testConnection(cred().copy(appPassword = "wrong-pass"))
        assertTrue("凭证错应归 AUTH", result is TestResult.Failed)
        assertEquals(SyncError.AUTH, (result as TestResult.Failed).reason)
    }

    @Test
    fun testConnection_deadPortIsNetworkError() = runBlocking {
        val dead = MiniDavServer().also { it.start() }
        val deadCred = WebDavCred(dead.baseUrl, "user", "app-pass")
        dead.stop()
        val fx = fixture()
        val result = fx.manager.testConnection(deadCred)
        assertTrue("网络错应归 NETWORK", result is TestResult.Failed)
        assertEquals(SyncError.NETWORK, (result as TestResult.Failed).reason)
    }

    @Test
    fun testConnection_invalidFormIsInvalidCred() = runBlocking {
        val fx = fixture()
        val result = fx.manager.testConnection(WebDavCred(baseUrl = "  ", username = "u", appPassword = "p"))
        assertTrue(result is TestResult.InvalidCred)
        assertEquals(WebDavCredIssue.URL_EMPTY, (result as TestResult.InvalidCred).issue)
    }

    // ------------------------------------------------------------ §4.2：setupAccount

    @Test
    fun setupAccount_createsNewBook() = runBlocking {
        val fx = fixture(exportCount = 3)
        val result = fx.manager.setupAccount(cred(), password.copyOf())

        assertTrue("首机建账应成功", result is SetupResult.Success)
        result as SetupResult.Success
        assertEquals("存量导出计数入结果", 3, result.exportedOps)
        assertNotNull("凭证已落本地", fx.account.load())
        assertNotNull("派生密钥已落本地", fx.store.savedKeys)
        assertNotNull("KDF 参数已落本地", fx.store.savedKdf)
        assertEquals("成员认领回传 syncId", fx.store.selfMemberId, result.memberSyncId)
        assertNotNull(fx.dao.findMemberByName("我"))
        assertEquals("首轮双向同步已触发", 1, fx.engine.calls)
        assertTrue(
            "MEMBER UPSERT 已记操作账",
            fx.dao.opLog.values.any { it.rowKind == RowKind.MEMBER.value && it.rowSyncId == result.memberSyncId },
        )
        assertTrue("云端 meta 已建", server.files.keys.any { it.startsWith("${WebDavRemote.DIR}/") })
    }

    @Test
    fun setupAccount_joinsExistingBookWithCorrectPassword() = runBlocking {
        val first = fixture(exportCount = 0)
        assertTrue(first.manager.setupAccount(cred(), password.copyOf()) is SetupResult.Success)

        // 第二台：同口令接入（peekMetaHeader 无口令发现 → KCV 相符）
        val second = fixture(exportCount = 0)
        val result = second.manager.setupAccount(cred(), password.copyOf())
        assertTrue("同口令应接入既有账", result is SetupResult.Success)
        assertEquals("首轮双向同步已触发", 1, second.engine.calls)
        assertEquals("meta 只写一次（If-None-Match:*）", 1, server.files.keys.count { !it.endsWith(".op") })
    }

    @Test
    fun setupAccount_wrongPasswordFailsBeforeAnyUserData() = runBlocking {
        val first = fixture(exportCount = 0)
        assertTrue(first.manager.setupAccount(cred(), password.copyOf()) is SetupResult.Success)

        // 第二台拿错口令接入：R-04 必须在下载任何用户数据前失败
        val second = fixture(exportCount = 2)
        val logFrom = server.requestLog.size
        val result = second.manager.setupAccount(cred(), "错误口令".toCharArray())

        assertTrue("口令不一致专属档", result is SetupResult.BadPassword)
        val slice = server.requestLog.subList(logFrom, server.requestLog.size).toList()
        assertTrue(
            "R-04：不得成功下载任何 .op 用户数据：$slice",
            slice.none { it.startsWith("GET ") && it.contains(".op") && (it.endsWith("200") || it.endsWith("206")) },
        )
        assertEquals("失败前不得导出存量", 0, second.exporter.calls)
        assertNull("失败前不得落凭证", second.account.load())
        assertNull(second.store.savedKeys)
    }

    // ------------------------------------------------------------ 存量导出确定性（RoomInitialExporter 真实现）

    @Test
    fun initialExportIsDeterministicAndIdempotent() = runBlocking {
        val source = FakeExportSource(sections = listOf(exportSection("sec-1", "生活")))

        val daoA = FakeSyncDao()
        val expA = RoomInitialExporter(source, daoA, FakeTx(listOf(daoA)), { "A" }, { null })
        assertEquals(1, expA.export())
        assertEquals("重复导出零副作用", 0, expA.export())

        val daoB = FakeSyncDao()
        val expB = RoomInitialExporter(source, daoB, FakeTx(listOf(daoB)), { "B" }, { null })
        assertEquals(1, expB.export())
        assertEquals("同内容跨设备同 opId（幂等折叠）", daoA.opLog.keys, daoB.opLog.keys)

        val daoC = FakeSyncDao()
        val sourceC = FakeExportSource(sections = listOf(exportSection("sec-1", "改过")))
        assertEquals(1, RoomInitialExporter(sourceC, daoC, FakeTx(listOf(daoC)), { "C" }, { null }).export())
        assertNotEquals("异内容异 opId（LWW 双留不丢）", daoA.opLog.keys, daoC.opLog.keys)
    }

    /**
     * 存量导出必须包含成员定义：resetSync 保留 members 而清空操作账，若导出不含 MEMBER，
     * re-setup 后新装/重装设备永远拉不到成员，entries.memberId 悬空、标签/按人统计失效。
     * 并经 OpApplier 回放证明导出的 MEMBER op 确实能在其他设备物化成员行。
     */
    @Test
    fun initialExportIncludesMemberDefinitions() = runBlocking {
        val source = FakeExportSource(
            members = listOf(
                MemberEntity(
                    syncId = "m-1",
                    name = "妈",
                    hidden = false,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    versionSeq = 3L,
                ),
            ),
            sections = listOf(exportSection("sec-1", "生活")),
        )
        val dao = FakeSyncDao()
        val exporter = RoomInitialExporter(source, dao, FakeTx(listOf(dao)), { "A" }, { null })

        assertEquals("SECTION + MEMBER 各记一条", 2, exporter.export())

        val op = dao.opLog.values.single { it.rowKind == RowKind.MEMBER.value }
        assertEquals("m-1", op.rowSyncId)
        assertEquals(OpType.UPSERT.value, op.opType)
        assertEquals("seq = 行 versionSeq", 3L, op.seq)
        assertNull("存在声明不观察历史", op.baseSeq)
        assertTrue("确定性 opId", op.opId.startsWith("export-m-1-"))
        val snapshot = JSONObject(op.payload)
        assertEquals("妈", snapshot.getString("name"))
        assertFalse(snapshot.getBoolean("hidden"))
        assertEquals(1_000L, snapshot.getLong("createdAt"))
        assertEquals("重复导出零副作用（countOpsOfRow 幂等）", 0, exporter.export())

        // 回放验证：其他设备拉到这条导出 op 后经 OpApplier 物化成员定义（标签/按人统计的前提）。
        // JVM 假件口径：行投影落 FakeRowStore.rows（生产由 RoomRowStore 直写 members 表）
        val store = FakeRowStore(dao)
        val applier = OpApplier(dao, store, FakeTx(listOf(dao, store)))
        val applied = applier.applyRemote(listOf(op.toModel().copy(opId = "remote-m-1", actorId = "B")))
        // ApplyResult.applied 按「该行操作全集」计数：导出的 LOCAL op + 回放的 REMOTE op 各 1；
        // 「直接应用而非挂起」由 deferred == 0 表达
        assertEquals("MEMBER 行操作全集应用、零挂起", 0, applied.deferred)
        assertEquals("导出 + 回放两条均已应用", 2, applied.applied)
        assertEquals(
            "成员定义已在其他设备的行投影中恢复",
            "妈",
            store.rows[RowKind.MEMBER to "m-1"]?.payload?.optString("name"),
        )
    }

    // ------------------------------------------------------------ R-05：resetSync

    @Test
    fun resetSync_deletesCloudAndClearsLocalSyncState() = runBlocking {
        val fx = fixture(exportCount = 0)
        assertTrue(fx.manager.setupAccount(cred(), password.copyOf()) is SetupResult.Success)
        val memberSyncId = fx.store.selfMemberId
        assertNotNull(memberSyncId)
        assertTrue(server.files.keys.any { it.startsWith("${WebDavRemote.DIR}/") })

        val logFrom = server.requestLog.size
        val result = fx.manager.resetSync()

        assertTrue("重置应成功（删云端失败不阻断）", result.isSuccess)
        val slice = server.requestLog.subList(logFrom, server.requestLog.size).toList()
        assertTrue("云端文件已逐个 DELETE：$slice", slice.any { it.startsWith("DELETE ") && it.startsWith("DELETE /dav/${WebDavRemote.DIR}/") })
        assertTrue("云端目录已清空", server.files.keys.none { it.startsWith("${WebDavRemote.DIR}/") })
        assertTrue("操作账已清", fx.dao.opLog.isEmpty())
        assertTrue("云端台账已清", fx.dao.fileTable.isEmpty())
        assertNull("派生密钥已清（忘口令出路）", fx.store.savedKeys)
        assertEquals(0L, fx.store.lastSyncAt)
        assertEquals("角标打回从未同步", SyncState.Never, fx.engine.state.value)
        assertNotNull("members 保留（re-setup 复用）", fx.dao.findMemberByName("我"))

        // 同名复用：re-setup 不产生第二个成员
        assertTrue(fx.manager.setupAccount(cred(), password.copyOf()) is SetupResult.Success)
        assertEquals(memberSyncId, fx.store.selfMemberId)
    }

    // ------------------------------------------------------------ V3：四路触发去抖 / 未配置静默

    @Test
    fun requestSync_debouncesAutoTriggersButNotManual() {
        val fx = fixture()
        // 未配置：静默无操作（S6）
        fx.manager.requestSync(SyncTrigger.FOREGROUND)
        assertEquals(0, fx.engine.calls)

        fx.account.save(cred())
        fx.store.saveDerived(keys, kdf)

        fx.manager.requestSync(SyncTrigger.FOREGROUND)
        fx.manager.requestSync(SyncTrigger.FOREGROUND)
        fx.manager.requestSync(SyncTrigger.COLD_START)
        assertEquals("自动触发 60s 去抖：三连发只跑一轮", 1, fx.engine.calls)

        fx.manager.requestSync(SyncTrigger.MANUAL)
        assertEquals("MANUAL 不去抖", 2, fx.engine.calls)

        fx.manager.requestSync(SyncTrigger.PERIODIC)
        assertEquals("去抖窗口内自动触发继续压制", 2, fx.engine.calls)
    }

    @Test
    fun requestSync_unconfiguredIsSilentNoop() {
        val fx = fixture()
        fx.manager.requestSync(SyncTrigger.MANUAL)
        fx.manager.requestSync(SyncTrigger.COLD_START)
        assertEquals("未配置不触发引擎", 0, fx.engine.calls)
        assertFalse(fx.manager.isConfigured())
    }

    // ------------------------------------------------------------ U-2：成员认领

    @Test
    fun claimMember_isUniqueByName() = runBlocking {
        val fx = fixture()
        val id1 = fx.manager.claimMember("妈")
        val id2 = fx.manager.claimMember("妈")
        assertNotNull(id1)
        assertEquals("同名复用既有成员", id1, id2)
        assertEquals(1, fx.dao.memberTable.size)
        assertEquals(
            "首记 + 复用补记各一条（复用路径也进操作账，防 resetSync 后成员定义失传）",
            2,
            fx.dao.opLog.values.count { it.rowKind == RowKind.MEMBER.value },
        )
        assertNull("空白名不认领", fx.manager.claimMember("   "))

        val id3 = fx.manager.claimMember("爸")
        assertNotEquals(id1, id3)
        assertEquals(2, fx.dao.memberTable.size)
    }

    /**
     * 修复回归：resetSync 保留 members 却清空操作账，re-setup 走 claimMember 复用路径——
     * 该路径原先不记任何操作，成员定义从此进不了操作日志，其他设备（新装/重装）拉不到它，
     * entries.memberId 悬空、成员标签/按人统计（R-18/R-20）静默失效。
     */
    @Test
    fun claimMember_reuseAfterResetRecordsMemberUpsert() = runBlocking {
        val fx = fixture()
        val first = fx.manager.claimMember("妈")!!
        assertTrue(fx.manager.resetSync().isSuccess)
        assertTrue("resetSync 已清操作账（R-05 前提）", fx.dao.opLog.isEmpty())
        assertNotNull("members 保留", fx.dao.findMemberByName("妈"))

        val reused = fx.manager.claimMember("妈")!!

        assertEquals("同名复用不重建成员", first, reused)
        assertEquals(1, fx.dao.memberTable.size)
        val ops = fx.dao.opLog.values.filter { it.rowKind == RowKind.MEMBER.value && it.rowSyncId == first }
        assertEquals("复用路径补记 MEMBER UPSERT", 1, ops.size)
        assertEquals("seq 取行 versionSeq", 1L, ops.single().seq)
        assertNull("存在声明口径", ops.single().baseSeq)
        val snapshot = JSONObject(ops.single().payload)
        assertEquals("妈", snapshot.getString("name"))
        assertFalse(snapshot.getBoolean("hidden"))
    }

    // ------------------------------------------------------------ U-12：裁剪后再同步仍收敛

    @Test
    fun afterTrimNextSyncRoundStillConverges() = runBlocking {
        val fx = fixture()
        val dao = fx.dao
        // 行 X（账目）：v1 老·非胜者、v2 老·胜者；S1（分区）胜者；C2（分类）到过已死（UPSERT + DELETE）
        dao.insertOp(op("s1", rowKind = RowKind.SECTION.value, rowSyncId = "S1", createdAt = 1_000L))
        dao.insertOp(op("x-v1", seq = 1L, createdAt = 1_000L))
        dao.insertOp(op("x-v2", seq = 2L, createdAt = 2_000L))
        dao.insertOp(op("c2-up", rowKind = RowKind.CATEGORY.value, rowSyncId = "C2", createdAt = 1_500L))
        dao.insertOp(
            op(
                "c2-del", rowKind = RowKind.CATEGORY.value, rowSyncId = "C2", opType = "DELETE",
                seq = 0L, baseSeq = 1L, createdAt = 1_600L,
            ),
        )

        // 第一轮：成功收尾裁掉 x-v1，其余全部保底
        val first = fx.manager.syncNow(SyncTrigger.MANUAL)
        assertTrue(first.success)
        assertNull("老非胜者已被裁", dao.getOp("x-v1"))
        assertNotNull("行胜者保底", dao.getOp("x-v2"))
        assertNotNull("DELETE 永不裁", dao.getOp("c2-del"))
        assertNotNull("C2 胜者保底（引用判定依据）", dao.getOp("c2-up"))
        assertNotNull("S1 胜者保底", dao.getOp("s1"))
        assertTrue("裁剪后 C2 操作全集不空（引用三分判据）", dao.countOpsOfRow(RowKind.CATEGORY.value, "C2") > 0)

        // 裁剪后 Lamport 连续：maxSeqOf 仍取保留胜者的 seq，本地编辑接着 v2 记账
        assertEquals(2L, dao.maxSeqOf(RowKind.ENTRY.value, "X"))
        val recorder = OpRecorder(dao, { "dev-test" }, { null })
        val editOpId = recorder.onUpsert(
            rowKind = RowKind.ENTRY,
            rowSyncId = "X",
            seq = 3L,
            baseSeq = 2L,
            snapshot = JSONObject("""{"amountCents":400}"""),
        )
        assertNotNull("裁剪后本地新操作照常落账", dao.getOp(editOpId))

        // 引用三分判定（裁剪后仍有效）：远端 ENTRY 引用「到过已死」的 C2 → 0 占位而非永久挂起
        val store = FakeRowStore(dao)
        store.rows[RowKind.SECTION to "S1"] = FakeRowStore.FakeRow(
            kind = RowKind.SECTION,
            syncId = "S1",
            versionSeq = 1L,
            payload = OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L),
        )
        val applier = OpApplier(dao, store, FakeTx(listOf(dao, store)))
        val remoteEntry = SyncOp(
            opId = "remote-e1",
            rowKind = RowKind.ENTRY,
            rowSyncId = "E1",
            opType = OpType.UPSERT,
            actorId = "B",
            memberId = null,
            seq = 1L,
            baseSeq = null,
            payload = OpCodec.entrySnapshot(
                type = 0, amountCents = 500, categorySyncId = "C2", sectionSyncId = "S1",
                entryTime = 1_000L, note = "引用死分类", reconciled = false, reimburseState = 0,
                createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
            ),
            createdAt = 1_000L,
        )
        val applied = applier.applyRemote(listOf(remoteEntry))
        assertEquals("引用死分类应占位落库而非挂起", 0, applied.deferred)
        assertEquals(
            "死分类引用按 0 占位（与 RoomRowStore 口径一致）",
            "0",
            store.rows[RowKind.ENTRY to "E1"]?.payload?.optString("categorySyncId"),
        )

        // 第二轮同步（裁剪后）：正常收敛——成功、零挂起；被新胜者取代的 x-v2 按规则变为可裁
        val second = fx.manager.syncNow(SyncTrigger.MANUAL)
        assertTrue("裁剪后下一轮同步正常", second.success)
        assertEquals(0, dao.countDeferredOps())
        assertNull("x-v2 已被 edit 串行取代成非胜者，按规则让裁（正确性论证仍覆盖：< 胜者 edit）", dao.getOp("x-v2"))
        assertNotNull("本地编辑（行胜者）保留", dao.getOp(editOpId))
        assertNotNull("远端新操作保留（本行胜者）", dao.getOp("remote-e1"))
        assertNotNull("上一轮保底的 DELETE 仍在", dao.getOp("c2-del"))
        assertEquals("日志余量精确：s1 + edit + c2-up + c2-del + remote-e1", 5, dao.opLog.size)
    }

    // ------------------------------------------------------------ U-17：R-21 到期清理释放照片

    /**
     * U-17 回归：syncNow 收尾的 R-21 到期清理必须与回收站页**三步同口径**——
     * listTrashBefore 取到期行 → purgeTrashBefore → 按到期 IMAGE 快照的 contentHash
     * 释放实体照片文件。旧实现只 purge：30 分钟周期同步几乎总先于用户进回收站页，
     * 被清留底是照片文件挂起期的唯一引用方，从此再无任何路径释放 ⇒ filesDir/images
     * 永久累积孤儿文件。
     */
    @Test
    fun syncNowPurgeReleasesExpiringTrashPhotos() = runBlocking {
        val fx = fixture()
        val dao = fx.dao
        val expiredHash = "a".repeat(64)
        val freshHash = "b".repeat(64)
        val now = System.currentTimeMillis()
        // 到期留底（IMAGE 快照携带 contentHash）+ 未到期留底 + 到期但非 IMAGE 的散条
        dao.insertTrash(
            ConflictTrashEntity(
                deleteOpId = "old-img-del", rowKind = "IMAGE", rowSyncId = "img-1",
                snapshot = OpCodec.imageSnapshot("e-9", expiredHash, 0).toString(),
                deletedAt = 1L, deletedByMemberId = null,
            ),
        )
        dao.insertTrash(
            ConflictTrashEntity(
                deleteOpId = "fresh-img-del", rowKind = "IMAGE", rowSyncId = "img-2",
                snapshot = OpCodec.imageSnapshot("e-9", freshHash, 0).toString(),
                deletedAt = now, deletedByMemberId = null,
            ),
        )
        dao.insertTrash(
            ConflictTrashEntity(
                deleteOpId = "old-entry-del", rowKind = "ENTRY", rowSyncId = "e-8",
                snapshot = OpCodec.entrySnapshot(
                    type = 0, amountCents = 100, categorySyncId = "c", sectionSyncId = "s",
                    entryTime = 1L, note = "", reconciled = false, reimburseState = 0,
                    createdAt = 1L, updatedAt = 1L, memberSyncId = null,
                ).toString(),
                deletedAt = 1L, deletedByMemberId = null,
            ),
        )

        val outcome = fx.manager.syncNow(SyncTrigger.MANUAL)

        assertTrue(outcome.success)
        assertNull("到期留底已被清", dao.getTrash("old-img-del"))
        assertNull("到期留底（非 IMAGE 散条）同样被清", dao.getTrash("old-entry-del"))
        assertNotNull("未到期留底保留", dao.getTrash("fresh-img-del"))
        assertEquals(
            "释放集合 = 到期 IMAGE 快照引用的 contentHash（不含未到期/非照片行）",
            listOf(setOf(expiredHash)),
            fx.releasedHashes,
        )
    }

    // ------------------------------------------------------------ 夹具

    /** manager 一体机：引擎/账户/导出注假件，DAV/远端走真实实现（对 MiniDavServer） */
    private class Fixture(
        val store: FakeSyncStore,
        val account: FakeAccountStore,
        val dao: FakeSyncDao,
        val exporter: FakeInitialExporter,
        val engine: FakeSyncRunner,
        val manager: SyncManager,
        val releasedHashes: MutableList<Set<String>>,
    )

    private fun fixture(exportCount: Int = 0): Fixture {
        val store = FakeSyncStore()
        val account = FakeAccountStore()
        val dao = FakeSyncDao()
        val tx = FakeTx(listOf(dao))
        val recorder = OpRecorder(dao, { "dev-test" }, { store.selfMemberId })
        val exporter = FakeInitialExporter(exportCount)
        val engine = FakeSyncRunner()
        val workDir = File(workRoot, "mgr-${System.nanoTime()}")

        val davFactory: (WebDavCred) -> MiniWebDavClient = { cred ->
            MiniWebDavClient(
                baseUrl = requireNotNull(cred.httpUrl()) { "凭证 URL 非法：${cred.baseUrl}" },
                username = cred.username,
                appPassword = cred.appPassword,
                newParser = { KXmlParser() },
            )
        }
        val remoteFactory: (WebDavCred, SyncKeys, KdfParams) -> WebDavRemote = { cred, k, params ->
            WebDavRemote(davFactory(cred), k, params, workDir)
        }
        val releasedHashes = mutableListOf<Set<String>>()
        val manager = SyncManager(
            engine = engine,
            store = store,
            account = account,
            crypto = crypto,
            syncDao = dao,
            recorder = recorder,
            exporter = exporter,
            tx = tx,
            davFactory = davFactory,
            remoteFactory = remoteFactory,
            scope = CoroutineScope(Dispatchers.Unconfined),
            photoRelease = { releasedHashes.add(it) }, // U-17：记录释放的 contentHash 集合
        )
        return Fixture(store, account, dao, exporter, engine, manager, releasedHashes)
    }

    private fun cred(): WebDavCred = WebDavCred(
        baseUrl = server.baseUrl,
        username = "user",
        appPassword = "app-pass",
    )

    /** U-12 裁剪用例的操作行工厂（默认 = 老已传已应用的 ENTRY UPSERT；口径同 OpTrimTest） */
    private fun op(
        opId: String,
        rowKind: String = RowKind.ENTRY.value,
        rowSyncId: String = "X",
        opType: String = "UPSERT",
        seq: Long = 1L,
        baseSeq: Long? = null,
        createdAt: Long,
        uploaded: Boolean = true,
        applied: Boolean = true,
    ) = SyncOpEntity(
        opId = opId,
        rowKind = rowKind,
        rowSyncId = rowSyncId,
        opType = opType,
        actorId = "A",
        memberId = null,
        seq = seq,
        baseSeq = baseSeq,
        payload = "{}",
        origin = "LOCAL",
        applied = applied,
        uploaded = uploaded,
        chunkName = null,
        createdAt = createdAt,
    )

    private fun exportSection(syncId: String, name: String) = SectionEntity(
        id = 1L,
        name = name,
        iconId = 1,
        note = "",
        budgetCents = 0,
        colorIndex = 0,
        sortOrder = 0,
        createdAt = 1_000L,
        syncId = syncId,
        versionSeq = 0L,
        updatedAt = 1_000L,
    )
}
