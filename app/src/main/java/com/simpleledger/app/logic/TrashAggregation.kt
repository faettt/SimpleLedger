package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.local.entity.TrashKind
import org.json.JSONObject

/**
 * 回收站「整包聚合」纯逻辑（A1/A2，无 Android / Room 依赖，JVM 可测）。
 *
 * 背景：删除分区 = 分区 + 其下账目**一并删除并留底**。回收站里会同时出现
 * 一条 SECTION 留底 + N 条 ENTRY 留底 + 各账目的 IMAGE 留底，但用户视角是
 * **一个包**：「分区『X』+ N 笔账目」，恢复也要**整包恢复**（分区与账目一起回来）。
 *
 * 聚合规则（定案）：
 * 1. SECTION 删除留底（kind = DELETE）作**包头**，吸纳：
 *    - 快照 `sectionSyncId` 指向它的 ENTRY 删除留底（成员账目）；
 *    - 这些账目名下、快照 `entrySyncId` 指向它们的 IMAGE 删除留底。
 * 2. 不属于任何分区包的 ENTRY 删除留底各自成包，吸纳自己名下的 IMAGE 留底。
 * 3. 其余（CATEGORY / MEMBER / 孤立 IMAGE / 一切 OVERWRITE 落败版）散条独立展示。
 *
 * 恢复顺序 = 分层序 SECTION → ENTRY → IMAGE（与 RowKind.layerIndex 一致），
 * 保证整包恢复后引用关系一次到位。
 */
object TrashAggregation {

    /** 分区整包（包头 = 分区留底，含成员账目与贴图） */
    const val GROUP_SECTION = 0

    /** 单笔账目（含其贴图留底） */
    const val GROUP_ENTRY = 1

    /** 散条（分类 / 孤立贴图 / 被修改覆盖的落败版） */
    const val GROUP_SOLO = 2

    /** 参与整包聚合的行类（SECTION 包头 / 成员账目 / 贴图）；其余行类一律散条 */
    private val AGGREGATED_KINDS = setOf(RowKindValue.SECTION, RowKindValue.ENTRY, RowKindValue.IMAGE)

    /**
     * 一个展示 / 恢复单元。
     *
     * [rows] 已按恢复顺序排好（SECTION → ENTRY → IMAGE）；[head] 是列表锚点行
     * （分区包 = 分区留底，单笔 = 账目留底，散条 = 自身）。
     */
    data class TrashGroup(
        val kind: Int,
        val head: ConflictTrashEntity,
        val rows: List<ConflictTrashEntity>,
    ) {
        /** 整包内账目条数（分区包 = 成员账目数；单笔 = 1；散条 = 0） */
        val entryCount: Int =
            rows.count { it.rowKind == RowKindValue.ENTRY }

        /** 整包内贴图留底条数 */
        val imageCount: Int =
            rows.count { it.rowKind == RowKindValue.IMAGE }

        /** 包内账目的 rowSyncId 集合（整包恢复的成员判定） */
        val entrySyncIds: Set<String> =
            rows.filter { it.rowKind == RowKindValue.ENTRY }.map { it.rowSyncId }.toSet()
    }

    /**
     * 把可见留底行聚合成展示单元。输入任意顺序，输出按「包内最新 deletedAt」新→旧，
     * 同刻按 deleteOpId 稳定排序（与 `listVisibleTrash` 的口径对齐）。
     */
    fun group(trashRows: List<ConflictTrashEntity>): List<TrashGroup> {
        val deleteRows = trashRows.filter { it.kind == TrashKind.DELETE }
        // 散条 = 非 DELETE 留底（OVERWRITE 落败版等）+ DELETE 里不参与聚合的行类
        // （CATEGORY / MEMBER 等——规则 3：散条独立展示，绝不丢弃）
        val soloPool = trashRows
            .filter { it.kind != TrashKind.DELETE || it.rowKind !in AGGREGATED_KINDS }
            .toMutableList()

        val sectionHeads = deleteRows.filter { it.rowKind == RowKindValue.SECTION }
        val entryPool = deleteRows.filter { it.rowKind == RowKindValue.ENTRY }.toMutableList()
        val imagePool = deleteRows.filter { it.rowKind == RowKindValue.IMAGE }.toMutableList()

        val groups = mutableListOf<TrashGroup>()

        // 1) 分区整包
        sectionHeads.forEach { head ->
            val members = entryPool.filter { snapshotString(it, "sectionSyncId") == head.rowSyncId }
            entryPool.removeAll(members.toSet())
            val memberIds = members.map { it.rowSyncId }.toSet()
            val images = imagePool.filter { snapshotString(it, "entrySyncId") in memberIds }
            imagePool.removeAll(images.toSet())
            groups += TrashGroup(
                kind = GROUP_SECTION,
                head = head,
                rows = listOf(head) + members.sortedBy { it.deleteOpId } + images.sortedBy { it.deleteOpId },
            )
        }

        // 2) 单笔账目（含自己的贴图）
        val singleEntries = entryPool.toList()
        singleEntries.forEach { entry ->
            entryPool.remove(entry)
            val images = imagePool.filter { snapshotString(it, "entrySyncId") == entry.rowSyncId }
            imagePool.removeAll(images.toSet())
            groups += TrashGroup(
                kind = GROUP_ENTRY,
                head = entry,
                rows = listOf(entry) + images.sortedBy { it.deleteOpId },
            )
        }

        // 3) 散条（OVERWRITE 落败版 / 孤立 IMAGE / CATEGORY 等）
        (soloPool + imagePool + entryPool).forEach { row ->
            groups += TrashGroup(kind = GROUP_SOLO, head = row, rows = listOf(row))
        }

        return groups.sortedWith(
            compareByDescending<TrashGroup> { g -> g.rows.maxOf { it.deletedAt } }
                .thenBy { g -> g.head.deleteOpId },
        )
    }

    /**
     * 一批留底行里 IMAGE 快照引用的 contentHash 集合（照片引用挂起的释放依据）。
     * 只认 rowKind = IMAGE 的行——ENTRY 快照不携带照片，照片引用都在 IMAGE 快照里。
     */
    fun imageHashesOf(trashRows: List<ConflictTrashEntity>): Set<String> =
        trashRows
            .filter { it.rowKind == RowKindValue.IMAGE }
            .mapNotNull { row ->
                runCatching { JSONObject(row.snapshot).optString("contentHash").takeIf { it.isNotBlank() } }
                    .getOrNull()
            }
            .toSet()

    /** 快照字符串字段读取（缺键 / 解析失败 → null，聚合宁可漏拉不误拉） */
    private fun snapshotString(row: ConflictTrashEntity, key: String): String? =
        runCatching { JSONObject(row.snapshot).optString(key).takeIf { it.isNotBlank() } }
            .getOrNull()
}
