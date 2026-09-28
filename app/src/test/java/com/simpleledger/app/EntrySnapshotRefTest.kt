package com.simpleledger.app

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.dao.CategoryDao
import com.simpleledger.app.data.local.dao.SectionDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.LedgerOpRecording
import com.simpleledger.app.sync.FakeSyncDao
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 账目快照引用序列化的回归钉子（AU-8）。
 *
 * 缺陷背景：`recordEntryUpsert` 曾把 0 占位 / 悬空引用强转成**空串**
 * （`categorySyncIdOf(0) ?: ""`），对端 OpApplier 的引用三分判定把空串判为
 * 「从未到过」→ 整行挂起、每轮重试永不落地——编辑一笔挂 0 分类的账目
 * （moveEntryToSection 保留 0 分类、duplicateEntry 复制 0 分类，设计上合法）
 * 后，该账目在其余设备**静默永不同步**。
 *
 * 修复后分类维解析不到行（含 0 占位）→ 改挂「未分类」哨兵（确定性 syncId，
 * 任何设备都可解析，语义 = UI 所示「未分类」）；分区维无哨兵，空串残留钉死在本
 * 测试，作为 sync 侧 OpApplier 后续把空串按死引用降级的对接锚点。
 */
class EntrySnapshotRefTest {

    private val categories = FakeCategoryDao().apply {
        rows[11L] = CategoryEntity(
            id = 11L, name = "餐饮", iconId = 2, type = EntryType.EXPENSE,
            syncId = "cat-sync-11",
        )
    }
    private val sections = FakeSectionDao().apply {
        rows[5L] = SectionEntity(id = 5L, name = "装修", iconId = 17, syncId = "sec-sync-5")
    }
    private val syncDao = FakeSyncDao()
    private val recording = LedgerOpRecording(
        sectionDao = sections,
        categoryDao = categories,
        ops = OpRecorder(syncDao, { "device-test" }, { null }),
    )

    /** 记一笔账目 UPSERT 并返回其载荷 */
    private fun recordedPayload(entry: EntryEntity): JSONObject = runBlocking {
        recording.recordEntryUpsert(entry, baseSeq = null)
        val op = syncDao.opLog.values.single { it.rowKind == RowKind.ENTRY.value }
        JSONObject(op.payload)
    }

    private fun entry(categoryId: Long, sectionId: Long, type: Int = EntryType.EXPENSE) = EntryEntity(
        id = 1L,
        type = type,
        amountCents = 12_300L,
        categoryId = categoryId,
        sectionId = sectionId,
        entryTime = 1_000L,
        syncId = "entry-sync-1",
        versionSeq = 1L,
    )

    @Test
    fun `zero placeholder category is serialized as unclassified sentinel`() {
        // 挂 0 分类（未分类口径）的账目：载荷必须引用哨兵而非空串——空串在对端
        // 会被判「从未到过」而整行挂起、永不同步
        val payload = recordedPayload(entry(categoryId = 0L, sectionId = 5L))
        assertEquals(
            SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE),
            payload.getString("categorySyncId"),
        )
        assertEquals("sec-sync-5", payload.getString("sectionSyncId"))
    }

    @Test
    fun `dangling non-zero category also falls back to sentinel`() {
        // 悬空引用（分类行已不在）与 0 占位同口径：对端可解析、UI 同为「未分类」
        val payload = recordedPayload(entry(categoryId = 999L, sectionId = 5L))
        assertEquals(
            SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE),
            payload.getString("categorySyncId"),
        )
    }

    @Test
    fun `resolved category keeps its real syncId`() {
        val payload = recordedPayload(entry(categoryId = 11L, sectionId = 5L))
        assertEquals("cat-sync-11", payload.getString("categorySyncId"))
    }

    @Test
    fun `sentinel matches the entry type`() {
        val payload = recordedPayload(
            entry(categoryId = 0L, sectionId = 5L, type = EntryType.INCOME),
        )
        assertEquals(
            SectionFirstSeed.Unclassified.syncId(EntryType.INCOME),
            payload.getString("categorySyncId"),
        )
    }

    @Test
    fun `placeholder section residual is the pinned empty string`() {
        // 分区维无哨兵：0 占位/悬空分区仍序列化为空串（对端会挂起该行）。
        // 彻底修复在 sync/ 侧（OpApplier.refOrNull 把空串按「到过且已死 → 0 占位」
        // 降级）；本断言钉住现状，作为该后续修复的对接锚点。
        val payload = recordedPayload(entry(categoryId = 11L, sectionId = 0L))
        assertEquals("", payload.getString("sectionSyncId"))
        assertEquals("cat-sync-11", payload.getString("categorySyncId"))
    }

    /* ---------- 最小假件：只真实现 getById，其余不可达 ---------- */

    private class FakeCategoryDao : CategoryDao {
        val rows = LinkedHashMap<Long, CategoryEntity>()
        override suspend fun getById(id: Long): CategoryEntity? = rows[id]

        override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<CategoryEntity> = rows.values.toList()
        override fun observeByType(type: Int): Flow<List<CategoryEntity>> = flowOf(byType(type))
        override suspend fun getAllByType(type: Int): List<CategoryEntity> = byType(type)
        override fun observeCandidates(sectionId: Long, type: Int): Flow<List<CategoryEntity>> =
            flowOf(byType(type))

        override fun observeGlobalByType(type: Int): Flow<List<CategoryEntity>> = flowOf(byType(type))
        override fun observeSectionByType(sectionId: Long, type: Int): Flow<List<CategoryEntity>> =
            flowOf(byType(type))

        override suspend fun getBySyncId(syncId: String): CategoryEntity? =
            rows.values.firstOrNull { it.syncId == syncId }

        override suspend fun listBySection(sectionId: Long): List<CategoryEntity> =
            rows.values.filter { it.sectionId == sectionId }

        override suspend fun deleteBySyncId(syncId: String) = error("不可达")
        override suspend fun findByName(name: String, type: Int): CategoryEntity? = error("不可达")
        override suspend fun countBySection(sectionId: Long): Int = error("不可达")
        override suspend fun insert(category: CategoryEntity): Long = error("不可达")
        override suspend fun update(category: CategoryEntity) = error("不可达")
        override suspend fun delete(id: Long) = error("不可达")
        override suspend fun moveEntries(fromCategoryId: Long, toCategoryId: Long) = error("不可达")
        override suspend fun detachFromSection(sectionId: Long): Int = error("不可达")
        override suspend fun nextSortOrder(type: Int, sectionId: Long?): Int = error("不可达")

        private fun byType(type: Int): List<CategoryEntity> =
            rows.values.filter { it.type == type }
    }

    private class FakeSectionDao : SectionDao {
        val rows = LinkedHashMap<Long, SectionEntity>()
        override suspend fun getById(id: Long): SectionEntity? = rows[id]

        override fun observeAll(): Flow<List<SectionEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<SectionEntity> = rows.values.toList()
        override suspend fun getBySyncId(syncId: String): SectionEntity? =
            rows.values.firstOrNull { it.syncId == syncId }

        override suspend fun insert(section: SectionEntity): Long = error("不可达")
        override suspend fun update(section: SectionEntity) = error("不可达")
        override suspend fun delete(id: Long) = error("不可达")
        override suspend fun deleteBySyncId(syncId: String) = error("不可达")
        override suspend fun moveEntries(fromSectionId: Long, toSectionId: Long) = error("不可达")
        override suspend fun nextSortOrder(): Int = error("不可达")
    }
}
