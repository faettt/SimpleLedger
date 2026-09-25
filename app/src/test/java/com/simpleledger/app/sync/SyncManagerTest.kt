package com.simpleledger.app.sync

import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        assertEquals(1, fx.dao.opLog.values.count { it.rowKind == RowKind.MEMBER.value })
        assertNull("空白名不认领", fx.manager.claimMember("   "))

        val id3 = fx.manager.claimMember("爸")
        assertNotEquals(id1, id3)
        assertEquals(2, fx.dao.memberTable.size)
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
        )
        return Fixture(store, account, dao, exporter, engine, manager)
    }

    private fun cred(): WebDavCred = WebDavCred(
        baseUrl = server.baseUrl,
        username = "user",
        appPassword = "app-pass",
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
