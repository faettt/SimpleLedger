package com.simpleledger.app.di

import android.content.Context
import androidx.room.withTransaction
import com.simpleledger.app.data.export.DataExporter
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.repo.ImageStorage
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.sync.AndroidNetworkStatus
import com.simpleledger.app.sync.SyncEngine
import com.simpleledger.app.sync.SyncManager
import com.simpleledger.app.sync.account.SyncAccount
import com.simpleledger.app.sync.account.SyncPrefs
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RoomTxRunner
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.photo.FilePhotoStore
import com.simpleledger.app.sync.photo.NetworkStatus
import com.simpleledger.app.sync.photo.PhotoRefs
import com.simpleledger.app.sync.photo.PhotoStore
import com.simpleledger.app.sync.photo.PhotoTransfer
import com.simpleledger.app.sync.photo.RoomPhotoRefs
import com.simpleledger.app.sync.InitialExporter
import com.simpleledger.app.sync.RoomInitialExportSource
import com.simpleledger.app.sync.RoomInitialExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 轻量手工依赖注入容器：单模块应用的务实选择。
 *
 * T-4 同步全家桶装配顺序有两处硬约束：
 * 1. [settings] 必须先于 [opApplier]——OpApplier 的 `settingSink` = `settings::applyRemote`；
 * 2. [opRecorder] 必须先于 settings 变更上报接线——`localChangeSink` 回调里要记 SETTING 操作。
 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val imageStorage: ImageStorage = ImageStorage(context)

    /** 同步偏好（T-1，实现 SyncStore 端口）：设备身份 / 认领成员 / KDF 材料 / 派生密钥 / 流量计数 */
    val syncPrefs: SyncPrefs = SyncPrefs(context)

    /** WebDAV 凭证（T-2，实现 AccountStore 端口，仅存本地） */
    val syncAccount: SyncAccount = SyncAccount(context)

    /** 加密原语（T-2）：Argon2id + HKDF + AES-256-GCM */
    val syncCrypto: SyncCrypto = SyncCrypto()

    /** 应用偏好（R-06 设置同步的行投影目标） */
    val settings: AppSettings = AppSettings(context)

    /** 本地写路径埋点（T-3）：与业务写同事务追加操作 */
    val opRecorder: OpRecorder = OpRecorder(database.syncDao(), syncPrefs)

    /** 设置变更 → SETTING 操作的记账协程（SharedPreferences 无事务可言，串行 + 尽力同拍） */
    private val settingOpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingOpMutex = Mutex()

    init {
        // R-06：本地设置改动 → SETTING 操作（rowSyncId = key，字段级 LWW；防回环在 AppSettings 内）
        settings.localChangeSink = { key, value ->
            settingOpScope.launch {
                settingOpMutex.withLock {
                    database.withTransaction {
                        val maxSeq = database.syncDao().maxSeqOf(RowKind.SETTING.value, key)
                        opRecorder.onUpsert(
                            rowKind = RowKind.SETTING,
                            rowSyncId = key,
                            seq = (maxSeq ?: 0L) + 1,
                            baseSeq = maxSeq,
                            snapshot = OpCodec.settingSnapshot(key, value),
                        )
                    }
                }
            }
        }
    }

    /**
     * 远端操作回放（T-3）：observed-remove 合并 + 行投影 + 回收站推导。
     * `settingSink` = [AppSettings.applyRemote]（R-06 字段级 LWW 的远端落地入口）。
     */
    val opApplier: OpApplier = OpApplier(database, database.syncDao(), imageStorage, settings::applyRemote)

    val repository: LedgerRepository = LedgerRepository(database, imageStorage, opRecorder)
    val exporter: DataExporter = DataExporter(context, repository)

    // ================================================================ T-4 同步全家桶

    /** 网络状态（R-17 Wi-Fi 门控的生产实现） */
    val networkStatus: NetworkStatus = AndroidNetworkStatus(context)

    private val photoStore: PhotoStore = FilePhotoStore(imageStorage::pathForHash)
    private val photoRefs: PhotoRefs = RoomPhotoRefs(database.entryDao())

    /** 照片管线（R-16 去重 / R-17 门控 / R-19 记账 / R-23 续传） */
    val photoTransfer: PhotoTransfer = PhotoTransfer(
        syncDao = database.syncDao(),
        photos = photoStore,
        refs = photoRefs,
        network = networkStatus,
        store = syncPrefs,
    )

    /** 照片断点续传半成品目录（R-23；cacheDir 可被系统回收，丢了就重传——内容寻址不重复计费） */
    private val photoWorkDir: File = File(context.cacheDir, "photo_partial")

    /** WebDAV 客户端工厂（testConnection / resetSync 删云端 / remoteFactory 共用） */
    val davFactory: (WebDavCred) -> MiniWebDavClient = { cred ->
        val url = cred.httpUrl() ?: error("WebDAV 地址非法: ${cred.baseUrl}")
        MiniWebDavClient(baseUrl = url, username = cred.username, appPassword = cred.appPassword)
    }

    /** 带密云端布局工厂（setupAccount / 引擎远端） */
    val remoteFactory: (WebDavCred, SyncKeys, KdfParams) -> WebDavRemote = { cred, keys, kdf ->
        WebDavRemote(dav = davFactory(cred), keys = keys, kdf = kdf, workDir = photoWorkDir)
    }

    /** 引擎远端供给：凭证 + 派生密钥齐备时可建；null = 未接入同步（引擎 SKIPPED） */
    private val remoteProvider: () -> WebDavRemote? = remoteProvider@{
        val cred = syncAccount.load() ?: return@remoteProvider null
        val keys = syncPrefs.loadKeys() ?: return@remoteProvider null
        val kdf = syncPrefs.kdfParams() ?: return@remoteProvider null
        remoteFactory(cred, keys, kdf)
    }

    /** 存量导出（setupAccount 一次；确定性 opId 幂等，见 RoomInitialExporter KDoc） */
    val initialExporter: InitialExporter = RoomInitialExporter(
        source = RoomInitialExportSource(database),
        syncDao = database.syncDao(),
        tx = RoomTxRunner(database),
        actorIdOf = { syncPrefs.deviceId },
        memberIdOf = { syncPrefs.selfMemberId },
    )

    /** 一轮同步编排（PUSH→PULL→MERGE→PHOTOS，S6 静默收口） */
    val syncEngine: SyncEngine = SyncEngine(
        syncDao = database.syncDao(),
        applier = opApplier,
        photo = photoTransfer,
        store = syncPrefs,
        remoteProvider = remoteProvider,
    )

    /** App 级同步门面（T-5 UI 唯一入口） */
    val syncManager: SyncManager = SyncManager(
        engine = syncEngine,
        store = syncPrefs,
        account = syncAccount,
        crypto = syncCrypto,
        syncDao = database.syncDao(),
        recorder = opRecorder,
        exporter = initialExporter,
        tx = RoomTxRunner(database),
        davFactory = davFactory,
        remoteFactory = remoteFactory,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}
