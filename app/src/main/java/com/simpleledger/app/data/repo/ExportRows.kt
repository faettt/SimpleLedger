package com.simpleledger.app.data.repo

import com.simpleledger.app.data.export.ExportRow
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money

/**
 * 把一条账目映射成 CSV 导出的一行（**纯函数**，便于单测这条不可动摇的隐私边界；
 * U-11 拆分自 LedgerRepository，函数体零变化）。
 *
 * ⚠️ 硬性约束：`amountYuan` 必须**始终写真实金额**。
 * 「隐藏金额」只是显示层偏好（`LocalHideAmounts` / `AppSettings.hideAmounts`），
 * 让用户自己看不清数字，而**不**改变数据本身；导出的文件是用户的备份，必须完整。
 * 若让导出跟随隐私开关，用户会在毫不知情下得到一份缺金额的备份——这是数据丢失级事故。
 * 本函数刻意不接收任何 hidden 参数，从签名上就杜绝后人「顺手」让它跟随隐藏开关。
 * 对应的回归断言见 `ExportPrivacyTest`。
 */
internal fun entryToExportRow(full: EntryFull): ExportRow = ExportRow(
    date = DateTimes.toLocalDate(full.entry.entryTime).toString(),
    time = DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)),
    typeLabel = if (full.entry.type == EntryType.EXPENSE) "支出" else "收入",
    amountYuan = Money.formatCents(full.entry.amountCents).replace(",", ""),
    category = full.category?.name ?: "未分类",
    section = full.section?.name ?: "未分区",
    sectionNote = full.section?.note ?: "",
    note = full.entry.note,
    imageCount = full.images.size,
)
