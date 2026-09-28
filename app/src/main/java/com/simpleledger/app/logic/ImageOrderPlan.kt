package com.simpleledger.app.logic

/**
 * 编辑账目时贴图顺序的重排纯逻辑（AU-4，无 Android / Room 依赖，JVM 可测）。
 *
 * 缺陷背景：[com.simpleledger.app.data.repo.EntryDraft.keptImagePaths] 的注释契约是
 * 「顺序即展示顺序」（EntryEditViewModel 按编辑器当前顺序传入），但旧保存路径只删
 * 不要的行、**不重写保留行的 sortOrder**，新图从 `keptCount` 起编号——删掉靠前的
 * 既有图再追加新图时，新图序号与保留图撞车（`ORDER BY sortOrder, id` 下按 id 破平），
 * 新图插进保留图中间：编辑页看到 C,D,E，保存重开变成 C,E,D，用户可感知的顺序错乱。
 *
 * 修法：保留行按 [keptPaths]（编辑器顺序）重写为 0..k-1，新图从 k 起连续编号，
 * 与「顺序即展示顺序」契约对齐。sortOrder 是贴图行的语义字段（IMAGE 快照携带），
 * 需要改写的行由调用方逐条记 UPSERT 埋点同步到其余设备。
 */
object ImageOrderPlan {

    /**
     * 保留行重排计划：返回 `path → 新 sortOrder`，**只含需要改写的行**
     * （顺序未变的行不产生写放大，普通编辑零噪音操作）。
     *
     * @param keptPaths 保留贴图的展示顺序（编辑器顺序，可含未落库的新路径——忽略）
     * @param currentSortOrder 现有落库行的 `path → sortOrder`
     */
    fun renumberPlan(keptPaths: List<String>, currentSortOrder: Map<String, Int>): Map<String, Int> =
        keptPaths.mapIndexedNotNull { index, path ->
            val current = currentSortOrder[path] ?: return@mapIndexedNotNull null
            (path to index).takeIf { current != index }
        }.toMap()

    /** 新图的起始序号 = 保留行数（紧接 0..k-1 之后连续编号） */
    fun nextOrderForImported(keptPaths: List<String>): Int = keptPaths.size
}
