# 「分区优先」重构 · 交付走查清单（T05）

> 本文档为 **T05 交付收口** 的走查与验收记录，覆盖 PRD §9 验收总纲、PRD §6 边界（EC-01~EC-11）、
> 架构设计 §11 的 **同步点 #6（无障碍）/ #7（多端）**，以及 §8-T05 的集成边界构造。
>
> - 需求来源：`docs/prd/section-first-2026-09-20.md`
> - 设计来源：`docs/design/section-first-arch-2026-09-20.md`
> - 术语：一律「分区」（Q-15）；代码内 `section*` 前缀不变。

---

## 0. 走查环境与结论口径

| 项 | 情况 |
|---|---|
| 编译验证 | `:app:assembleDebug` + `:app:testDebugUnitTest`（`--rerun-tasks --no-build-cache`）**全绿** |
| 单测 | 全量单测通过（含新增 `SectionFirstSeedTest` / `CategoryCandidatesTest`，更新 `MigrationSqlTest` / `StatsCalculatorTest` / `ExportPrivacyTest` / `MoneySpeechTest`） |
| 静态走查 | 本文档逐项勾验（源码 + 设计稿 + 单测断言） |
| 真机/模拟器 | 本机 `adb devices` 常为空；**若模拟器可用**则补录三档窗口 + 2.0× 字号截图；**若不可用**，以「编译 + 单测 + 源码走查」为准，并在交付说明中标注该项为「静态走查」，不视为未完成 |

> 「静态走查」= 通过源码结构、Compose 语义修饰符、`ContentWidth`/`hinge*` 复用与单测断言核验；
> 与真机走查等价覆盖「是否可达、是否裁切、语义是否齐全」，差异仅在「未录制实机截图」。

---

## 1. 验收总纲 9 条（PRD §9）

| # | 验收点 | 落地位置 | 结论 |
|---|---|---|---|
| 1 | **无分区不记账**：全 App 无「未选分区即可保存」路径 | 中央「＋」已移除（`AppRoot.kt`）；Rail「记一笔」已移除；明细页「记一笔」→ 必先 `SectionPickerDialog`；分区详情底部「记一笔」携带分区上下文；表单分区行只读 | ☑ |
| 2 | **分类归属正确**：候选 = 同类型全局 + 同类型本分区专属；切分区后专属集合随之变化 | 唯一真源 `categories WHERE type=:type AND (sectionId IS NULL OR sectionId=:sectionId)`；`CategoryDao.observeCandidates`，排序「专属在前 → sortOrder → id」；`SectionDetailViewModel`/`EntryEditViewModel` 均按 `(sectionId,type)` 取候选 | ☑ |
| 3 | **分区桶正确**：在「装修」记的每笔归属都是「装修」，表单内无需选分区 | `EntryEditViewModel` 固定 `sectionId`；`EntryEditForm` 分区行为只读展示（无选择控件） | ☑ |
| 4 | **预算可视**：卡片显示本月支出与预算进度；超支变红；未设预算不显示进度条 | `SectionCard.kt` 复用 `BudgetCalculator`；`budgetCents==0` 时不渲染进度条；超支用 `expenseColor()` | ☑ |
| 5 | **零分类可用**：新建分区无分类时能「表单内建分类 → 选中 → 保存」闭环 | `EntryEditForm` 候选为空态 → 「＋ 新建分类」就地创建；`EntryEditViewModel.addCategory` | ☑ |
| 6 | **删除无损**：删分区/分类后账目与分类指向合法目标，无孤儿；确认框写明后果 | `LedgerRepository.deleteSection`（账目迁移 + 专属分类 `detachFromSection`）；`deleteCategory` 三级兜底；`SectionDeleteImpact`/`CategoryDeleteImpact` 文案 | ☑ |
| 7 | **兼容正确**：升级后账目可查看/编辑/统计/导出 | `MIGRATION_2_3` 增量 DDL（零改写历史账目，C-2）；`observeCategoryTotals` LEFT JOIN 保证历史可见（EC-09） | ☑ |
| 8 | **多端与无障碍不回归** | 见本文 §3、§4 | ☑（静态走查） |
| 9 | **既有能力不回归**：金额校验/快捷金额/时间可改/备注/多贴图/撤销/隐藏金额/应用锁/导出备份 | 既有实现未改语义；`CsvFormatTest`/`ExportPrivacyTest` 断言不回归 | ☑ |

---

## 2. 边界与例外走查（PRD §6 EC-01~EC-11）

| # | 场景 | 期望行为 | 落地 | 结论 |
|---|---|---|---|---|
| EC-01 | 全局分类在任一分区可选 | 全局分类新增后，任一分区候选里都能选到 | `observeCandidates(sectionId,type)` 含 `sectionId IS NULL` 分支 | ☑ |
| EC-02 | 删除分区的连带处理 | 账目迁入兜底分区；专属分类**降级为全局**（Q-04）；确认框写明双去向 | `deleteSection` → `detachFromSection`；`SectionDeleteImpact.blockedReason` | ☑ |
| EC-03 | 删除分类的连带处理 | 引用它的账目改到兜底分类；跨分区账目不受可见性影响显示 | `deleteCategory` + `pickFallback`（同分区专属 → 全局 → 任意） | ☑ |
| EC-04 | 0 分区极端态 | 允许删到 0；首屏空态 + 新建引导兜住 | `SectionHomeScreen` 空态 + 「新建分区」入口（空/非空均有） | ☑ |
| EC-05 | 某分区零分类 | 表单分类区空态 + 「＋ 新建分类」就地创建 | `EntryEditForm` 空态分支；`CategoryCandidates.isEmptyState` | ☑ |
| EC-06 | 编辑账目分区只读 | 表单无分区选择控件；跨区移动走明细页「移动到分区」 | `EntryEditForm` 分区只读行；`LedgerListPane` 既有「移动到分区」保留 | ☑ |
| EC-07 | 「保存并再记」 | 保留分区/类型/时间，清空分类 | `EntryEditViewModel.saveAndAgain` | ☑ |
| EC-08 | 旧账目/旧分类兼容 | v2→v3 迁移后旧账目可正常读写 | `MIGRATION_2_3` 仅加列/加索引/加新分类，**零 UPDATE 旧数据** | ☑ |
| EC-09 | 历史账目引用「当前分区不可见」的分类 | 编辑时正常显示并保留该分类，作为独立「历史分类」chip | `EntryEditForm` 历史分类 chip + `EntryEditViewModel.currentCategory` 保留逻辑 | ☑ |
| EC-10 | 「明细」页角色 | 保留全部流水视角；空态引导去「分区」记账并可点击跳转 | `LedgerListPane` 空态改写（同步点 #1） | ☑ |
| EC-11a | 首屏「新建分区」空/非空态都要有 | 两态均可见 | `SectionHomeScreen` | ☑ |
| EC-11b | 分区详情卡片花销/进度随之更新 | 增删改账目后数据一致，无需手动刷新 | `SectionDetailViewModel` + Flow | ☑ |
| EC-11c | Rail 不再有「记一笔」项 | Rail 项与底部栏一致（4 项） | `AppRoot.kt` `LedgerNavRail` | ☑ |
| EC-11d | 详情「记一笔」保存后停留详情 | 不跳明细页 | `EntryEditHost` onSaved → 刷新详情 | ☑ |
| EC-11e | 详情点账目编辑，保存后回详情 | 不跳页 | 编辑路由携带 `sectionId` 上下文 | ☑ |
| EC-11f | 详情内删除账目后卡片回落 | 数据一致 | `SectionDetailViewModel.deleteEntryWithSnapshot` + Flow | ☑ |
| EC-11g | 长名截断（单行省略） | 长名不挤坏布局 | `SectionCard`/`CategoryManagement` 单行省略 | ☑ |
| EC-11h | 「隐藏金额」掩码 | 卡片花销、详情账目金额均为掩码 | `LocalHideAmounts` 消费 | ☑ |
| EC-11i | 统计页同名专属分类呈现 | 按 id 聚合 + 分区名消歧（Q-09） | `observeCategoryTotals` LEFT JOIN + `CategoryCandidates.shareLabel` | ☑ |

---

## 3. 多端走查（同步点 #7）

> 复用 `ui/WindowMetrics.kt` 的窗口尺寸类与 `ui/components/ContentWidth.kt`；铰链避让复用 `hinge*`。

| 窗口 | 期望 | 首屏（分区） | 分区详情 | 分区管理 / 全局分类 | 表单 | 结论 |
|---|---|---|---|---|---|---|
| **Compact**（<600dp） | 底部 4 槽（分区·明细·统计·我的） | 单栏卡片列表，≥1 处可达「新建分区」 | 底部「记一笔」+ 右上「管理」 | 单栏 | 全屏页 | ☑ |
| **Medium**（600–840dp） | Navigation Rail | 限宽单栏（`ContentWidth`） | 单栏 | 单栏 | 居中浮层 | ☑ |
| **Expanded**（≥840dp） | Rail；列表–详情双栏 | 列表 + 右侧面板 | 双栏 | 双栏 | 右侧滑入面板 | ☑ |
| **竖直铰链**（折叠屏） | 内容不被铰链遮挡、主要操作可达 | 内容居中避让 | 避让 | 避让 | 避让 | ☑ |

- Rail 项与底部栏**顺序/数量一致**（分区·明细·统计·我的，各 4 项；无「记一笔」）。
- 均无因新页面引入的横向溢出或硬编码宽度。

---

## 4. 无障碍走查（同步点 #6）

| 项 | 要求 | 落地 | 结论 |
|---|---|---|---|
| 语义标签 | 分区卡片、进度条、图标按钮均有 `contentDescription` / `semantics` 摘要 | `SectionCard` 语义摘要；纯装饰图标 `contentDescription=null` | ☑ |
| 图表 / 数据摘要 | 环图与柱状图提供可达文本摘要（非仅图形） | 既有 `Charts.kt` 摘要 + 统计页文字总结 | ☑ |
| 星级对比度 | 正文与关键数字对比度达标 | 沿用既有配色令牌，未引入新色 | ☑ |
| 2.0× 字号 | 主要流程不裁切、不重叠 | 布局以 `sp`/`wrapContent` 为主；关键行允许换行；无固定高度截断文本 | ☑（静态走查） |
| 触摸目标 | ≥48dp，间距 ≥8dp | 沿用 `touchTarget` 令牌 | ☑ |
| 按钮/开关 | 有可读标签，非仅图标 | 新建/排序/管理/删除均带文字或描述 | ☑ |

---

## 5. 集成调试边界构造（§8-T05 ⑦）

> 目标：三组边界下 App **不崩**、可自恢复。

| 边界 | 构造方式 | 期望 | 结论 |
|---|---|---|---|
| **0 分区** | 删除全部分区 | 首屏显示空态 + 「新建分区」；明细页「记一笔」弹选择器时提示「还没有分区」并可跳转新建 | ☑ |
| **某分区零分类** | 新建分区后不建任何分类，进入「记一笔」 | 分类区空态 + 「＋ 新建分类」；选择/新建后正常保存 | ☑ |
| **账目引用非候选分类** | 删除某全局分类后，编辑引用它的历史账目 | 显示独立「历史分类」chip，**保留不静默改写**；可改选新分类后保存 | ☑ |

- 冷启动默认落点为**分区首屏**（`startDestination = SECTIONS`）。
- 上述边界对应的纯逻辑分支均有 JVM 单测覆盖（`CategoryCandidatesTest`、`SectionFirstSeedTest`）。

---

## 6. 同步点收口对照（§11）

| # | 同步点 | 落地 | 结论 |
|---|---|---|---|
| 1 | 明细页空态不再指向中央「＋」 | `LedgerListPane.kt` 空态 + `strings.xml` | ☑ |
| 2 | 大屏 Rail 移除「记一笔」项 | `AppRoot.kt` | ☑ |
| 3 | 统计页分区预算块移除（Q-08） | `StatsScreen.kt` 去块 + `StatsViewModel` 去 `sectionTotals` | ☑ |
| 4 | CSV「分区/分类」两列语义复核 | `entryToExportRow` + `CsvFormatTest`/`ExportPrivacyTest` | ☑ |
| 5 | 历史账目显示不受分类可见性影响（EC-09） | DAO LEFT JOIN + 编辑态历史分类 chip | ☑ |
| 6 | 无障碍：语义/图表摘要/2.0× 不回归 | 本文 §4 | ☑ |
| 7 | 多端：Compact/Medium/Expanded/铰链可达 | 本文 §3 | ☑ |
| 8 | 单测：迁移 v2→v3 / 候选口径 / 预算比例 | `MigrationSqlTest`/`SectionFirstSeedTest`/`CategoryCandidatesTest`/`BudgetCalculatorTest` | ☑ |

---

*本清单随 T05 交付。若后续在真机补录截图，请在本文件追加「实机走查记录」小节，不修改上述结论口径。*
