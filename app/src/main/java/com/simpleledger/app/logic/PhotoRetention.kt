package com.simpleledger.app.logic

/**
 * 照片引用挂起的实体文件保留判据（A2 照片挂起，纯逻辑，JVM 可测）。
 *
 * 定案：留底期内 `entry_images` 行与 `filesDir/images/<sha256>.jpg` 实体文件
 * **不物理删除**——恢复后须完整重建照片引用；只有「永久删除」（手动清空 /
 * 90 天到期）才物理删除文件。
 *
 * 判据：实体文件删除当且仅当
 * - 业务侧引用为零（`EntryDao.countByContentHash == 0`），且
 * - 未消化留底里也没有引用（`SyncDao.countVisibleTrashRefs == 0`）。
 *
 * 误宽不误漏：留底快照的 LIKE 匹配可能多算引用 → 文件多留一会，无害；
 * 反向（漏算）会丢用户照片，绝不允许。
 */
object PhotoRetention {

    /**
     * @param liveRefCount 业务表（entry_images）对该 contentHash 的引用数
     * @param suspendedTrashRefCount 未消化留底（sync_trash.resolved = 0）对该 contentHash 的引用数
     * @return true = 两个引用面都归零，实体文件可物理删除
     */
    fun shouldDeleteFile(liveRefCount: Int, suspendedTrashRefCount: Int): Boolean =
        liveRefCount <= 0 && suspendedTrashRefCount <= 0
}
