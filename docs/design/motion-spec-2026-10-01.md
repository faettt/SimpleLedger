# 简账动效规格 v2 ——「有重量的纸」

日期：2026-10-01 · 作者：motion-arch（动效从零重构 · task-6）
代码真源：`app/src/main/java/com/simpleledger/app/ui/theme/Motion.kt`（v2 已重写）
上一代语言：「纸的物理」（v1，同文件旧版；视觉版式真源仍是
`docs/design/journal-style-spec-2026-09-20.md`，本规格只管**运动**，不动版式）

---

## 0. 文档定位

本文回答四件事：

1. **审计**：现有动效调用点全量清单（附录 A），以及它们暴露的结构性问题；
2. **痛点假设**：用户「整体不满意」最可能来自哪里，各自怎么解（§2）；
3. **新动效语言**：从零推演的 v2 规格——双轨制、缓动、页面转场模式学、微交互、
   图表生长、减少动效契约（§3–§9）；
4. **迁移指引**：Motion.kt v2 如何在不碰任何调用点文件的前提下落地，
   队友后续怎么迁、迁完删什么（§10–§11）。

数值凡标 **[不变]** 者与 v1 相同（多数经过真机 A/B 拍板，重写不动它们）；
标 **Δ** 者为 v2 有意改变，均列真机验收项（§12）。

---

## 1. 审计摘要

全量 grep 盘点见**附录 A**（14 个文件 × 30 处调用点）。结构性结论先给：

| # | 结构性问题 | 证据 | v2 对策 |
|---|---|---|---|
| S1 | 转场**方向判定散落**在 AppRoot 的 when 链 | AppRoot.kt L272–404：6 组方向 lambda × 4 分支路由判断；v1.5.6 修的「双运动」bug 正源于此 | `SlRelation` 关系表 + `rememberSlPageMotion()`，Motion.kt 独家装配（§6.1） |
| S2 | **减少动效降级靠调用点自觉** | AppRoot 手动捕获 `LocalReduceMotion`；SlipCard 自判 reduceMotion | 新 API 在 `rememberSlPageMotion()` / 修饰符内部统一读取，调用点不再自带 if（§9） |
| S3 | 令牌是**平铺常量堆**，缺语义域 | v1 `SlMotion` 单 object 混装时长/位移/形变/转场/曲线 | 拆 `SlEasing` / `SlTempo` / `SlShift` / `SlFeel` 四域 + 双轨规格生成器（§10） |
| S4 | 状态类动画全 tween，**快速连点时从零重启** | IndexTab 五连 animate*AsState、环图扇区带、图例底垫等 14 处 | 状态轨弹簧化（§4） |
| S5 | 图表**每次回到页面都重演** 660ms 生长 | `rememberSlChartTimeline` 用 `remember{Animatable}`，导航离开即失忆 | 生长进度 `rememberSaveable` 留存：一次生长（§8） |
| S6 | 同一职责**双实现**：SlipCard 自实现 hover 拈起与 `slHoverLift` 平行 | SlipCard.kt L97–106 vs Motion.kt slHoverLift | 迁移时合并到 `slHoverLift`（§11）；v2 两者的量值差异记录在案 |
| S7 | 文档失准（只读文件，记录不改） | Accessibility.kt L24 称「ui 模块内部的 MotionDurationScaleImpl」——仓库内并无此类，时长缩放实际由 Compose UI 内部（AndroidComposeView 提供 `MotionDurationScale`）实现；v1 Motion.kt 头注「时长只有四档」实列 5 值 | v2 文档与代码同步修正；旧文件两处错述待迁移 PR 顺手修 |

---

## 2. 痛点假设：用户可能为什么不满意

「整体不满意」没有逐条 bug 单，只能从历史与代码结构反推最可能的来源。
按可能性排序，每条给出**解法**与落点：

### P1 转场双运动（有前科，必须用原则封死）
**假设**：容器变换对曾「页面横移缩放 + 纸头形变」两层运动同时跑（v1.5.6 已把页面层
改为纯淡化），但残留观感问题：页面层 150ms 就静止，纸头还要飞到 320ms——
**背景先死、主角未落**，进详情像「先换页、再贴纸」而非「一张纸长大」。
**解法**：确立**一纸一动**原则（§6.3）——每次转场只有一个运动主角；
容器对的页面层淡化允许缩短（150ms），纸头飞行是唯一的空间运动。
双运动回归被写成规格红线，而不是靠某次修复合住。

### P2 跨页时长不一致
**假设**：同类操作在不同页速度不同——底部签互切 250/150ms，纸片菜单 250/150ms，
全屏表单 320/150ms，M3 内置组件（BottomSheet/Dialog，约 300–400ms emphasized）
又是另一套。扫读时「这台机器的每一页有自己的表」。
**解法**：三档节奏契约 **150 / 250 / 320**（[不变]，均经 A/B 拍板）+
所有规格经生成器发放（`slScene` / `slState`），禁止调用点散写时长曲线。
M3 内置组件不改（其 emphasized 曲线族与本规格同源，观感本就一致，见 §5）。

### P3 按压 / 入场反馈弱
**假设**：按压缩放 0.975（2.5%）在小签上仅 ≈1.4dp，肉眼接近不可见；
悬停拈起 1dp 同病。「按了像没按」会直接读成「这应用很木」。
**解法**：Δ 按压深度 0.975 → **0.96**（56dp 签上 ≈2.2dp，卡片上 ≈7dp，可感而不夸张）；
Δ 抬起从近临界阻尼弹簧（0.85/400）改为 **0.8 阻尼 + 整定 120ms** 的弹簧——
抬起有一次极轻的 settle，「纸被压下又回弹到位」而不是死的定格；
Δ 悬停拈起 1dp → **2dp**。三项全部列入真机验收（§12），观感不妥即回调数值，
**契约层（70ms 内响应）不动**。

### P4 状态动画没有物理，连点时「重启」
**假设**：快速连点底部签、快速切换扇区选中时，tween 每次从当前值重起步
（速度不续接），观感是「一顿一顿」，不像被手推着走。
**解法**：状态轨全面弹簧化（§4）：`slState` 生成的弹簧可中断、可续速，
连点越快越顺滑——这是「现代动效」手感的核心来源。

### P5 图表反复生长，表演疲劳
**假设**：统计页的环图/条形图/日柱在每次进入时都从零生长一遍
（660ms × 3 图），切签回来又来一遍。第一次是「数据长出来」，第三次是「动画关不掉」。
**解法**：Δ 生长进度经 `rememberSaveable` 跨导航留存——同一图表**只生长一次**，
返回时以完整形态出现。生长语义保留在「第一次到达」（§8）。

### P6 方向判定散落，改一处坏三处
**假设**：用户感知到的「转场乱」背后是维护性病灶：tab 侧与对端侧各有一条
when 链，六个分支手写路由判断，v1.5.6 的双运动就是分支绑错模式的产物。
**解法**：`SlRelation`（Chapter/Push/Form/Container）单一关系表 +
`rememberSlPageMotion()` 独家装配四模式 × 四方向（§6）。迁移后 AppRoot 只剩
「路由对 → 关系」一张表，不再出现裸 tween/slide 组合。

### P7 降级靠各处自觉，容易漏
**假设**：减少动效降级逻辑散在调用点（AppRoot 六个 lambda 各自 if、SlipCard 自判），
迁移与新增页面时容易漏掉一处，用户开了「移除动画」仍看到位移。
**解法**：降级内建：`rememberSlPageMotion()` 与三个修饰符**内部**读
`LocalReduceMotion`（§9），调用点无从忘记。两层降级契约原样存续。

---

## 3. 哲学：从「纸的物理」到「有重量的纸」

### 3.1 去与留

**留**（它们是对的，且多数经真机拍板）：

- **纸的隐喻**——与手账视觉语言同源：纸片、垫纸、索引贴、轻放/抽走的动词；
- **反馈先于表演**——按压 70ms 内响应，动效是反馈不是节目；
- **三条缓动的数值**——恰为 M3 emphasized 家族（§5），与 M3 内置组件同曲线
  是「跨页一致」的根基；
- **位移三档 8/24/32dp** 与「滚动路径零入场」；
- **图表生长**（基准边不动、错峰单时间线）与**两层减少动效**（产品级契约）；
- **纸页对偶四段几何**——v1.5.6 后空间路径 push/pop 严格互逆，几何自洽
  （NavAlphaReturnStart 与 NavAlphaUnder 的解耦裁定保留）。

**去 / 改**（v1 的病灶或未竟之处）：

- 「**几乎不弹**」→ 状态轨引入弹簧：v1 把纸做成了无摩擦的理想定格，快速连点时
  没有「被手推着走」的连续感。v2：纸有**质量**，压下回弹有一次 120ms 的轻 settle。
  不是果冻（阻尼 0.8，过冲可见但小），是「纸落回桌面」的那一下；
- 「**轻**」的量感 → 「**有重量**」：反馈加深到可感（0.96 / 2dp）。轻不等于弱；
- **平铺令牌堆** → 四语义域 + 双轨生成器：v1 的 SlMotion 什么都有，
  调用点只能靠注释理解哪个数能用在哪；v2 的域对象让「乱用」在 API 面上就困难；
- **转场装配散在 UI 层** → Motion.kt 独家装配（§6）；
- **图表无脑重放** → 一次生长（§8）。

### 3.2 一句话语言

> **页面在节拍器上，微交互在弹簧上；每次转场只有一个主角；
> 图表长一次，反馈快于表演；关掉动画，一切照常工作。**

---

## 4. 双轨制：时长体系 vs 弹簧物理（问题 a）

**结论：不做全面弹簧化，采用「场景轨 tween + 状态轨 spring」双轨制。**

### 4.1 为什么场景轨（页面转场/容器变换/浮层）必须保持时长契约

1. **predictive back（targetSdk 37 已强制开启）**：manifest 已有
   `enableOnBackInvokedCallback="true"`，navigation-compose 2.10 的 NavHost 用
   `SeekableTransitionState` 把返回手势进度直接灌进转场——手势拖到哪，转场走到哪。
   **seek 要求时长确定的规格**：tween 沿时间轴确定性插值，拖拽手感线性可逆；
   spring 没有固定时长，seek 表现差（进度-时间映射漂移）。
2. **编队同步**：纸页对偶的层次感来自「底页 250ms 早于顶页 320ms 收尾」的 70ms 时差。
   弹簧各自自由收敛，时差编队会被隐性破坏。
3. **类型与口径**：NavHost 转场、sharedBounds 的 boundsTransform、animateItem
   都要求 `FiniteAnimationSpec`；且 150/250 档经 A/B 拍板，不能被弹簧隐性改写。

### 4.2 为什么状态轨（按压/悬停/选中/形变）必须弹簧

1. **可中断性**（现代方向的核心）：spring retarget 续接当前速度——快速连点、
   选中态在扇区间移动，动画是「被新目标吸走」而不是「从头重来」；
2. **物理表达**：质量-阻尼-刚度就是「纸有重量」的实现载体；
3. **零额外成本**：`spring(dampingRatio, stiffness)` 一个函数，无状态、可组合。

### 4.3 桥接：让弹簧也有「档位」

两轨若各说各话，节奏就散了。桥接是 `slState(settleMs)`：
把时长档翻译成弹簧刚度——**整定判据**取 4/ω ≈ 2% 残余（临界阻尼标准近似），
即 `ω = 4000/settleMs`（ms→rad/s），`stiffness = ω²`：

| 档（ms） | 刚度 | 近似 Spring 常量 | 用途 |
|---|---|---|---|
| 120 | 1111 | ≈ Medium(1500) 略软 | 按压抬起 settle |
| 150 | 711 | Medium 与 MediumLow 之间 | 小状态（选中/悬停/色） |
| 250 | 256 | ≈ MediumLow(400) 略软 | 列表项 placement |

档位感知等价（弹簧 ≈ 在档位时长内收敛），但可中断、可续速——
**弹簧没有背叛节奏，节奏不再依赖停顿**。

### 4.4 时长档（SlTempo）[数值不变]

| 档 | ms | 语义 |
|---|---|---|
| Press | 70 | 反馈·按下（契约：70ms 内响应） |
| Release | 120 | 反馈·抬起 settle（弹簧整定目标） |
| Fast | 150 | 反馈·小状态（选中、chip、色）；场景·快离场 |
| Base | 250 | 场景·标准（浮层、卡面、共享内容交叉、列表项） |
| Slow | 320 | 场景·整页（纸页对偶顶页、铺页） |
| ChartGrow / ChartStagger / Cap | 420 / 30 / 8 | 图表时间线（§8） |
| Spin | 1920 | 循环类专用（同步墨点一圈；v1 由 `SlowMs*6` 派生，v2 给独立名分） |

---

## 5. 缓动体系（问题 b）

四条曲线，**数值全部 [不变]**（v1 三条恰为 M3 令牌，验证了手感；v2 只换名字与归属）：

| 令牌 | 贝塞尔 | = M3 | 用途 |
|---|---|---|---|
| `SlEasing.Enter` | (0.05, 0.7, 0.1, 1) | emphasized-decelerate | 入场/展开/settle：快起慢收，到位即停 |
| `SlEasing.Exit` | (0.3, 0, 0.8, 0.15) | emphasized-accelerate | 离场/收拢/抽走：慢起快收，末端加速 |
| `SlEasing.Standard` | (0.2, 0, 0, 1) | emphasized | 无方向语义的切换/形变（章间、状态对换） |
| `SlEasing.Linear` | — | — | 循环与图表时间线（首尾相接无顿挫） |

**命名论证**：v1 叫 PaperOut/PaperIn（隐喻命名），问题是要先懂隐喻才知道用哪条；
v2 按功能命名 Enter/Exit——**曲线选择从「回忆隐喻」变成「读名字」**。
与 M3 同数值还带来一个免费收益：M3 内置组件（BottomSheet/Dialog/涟漪）走的就是
emphasized 家族，应用自绘与内置组件天然同一曲线语言，P2 的「跨页一致」在曲线维度自动成立。

**曲线×方向契约**（沿用 v1 裁定）：让位/归位/settle 类一律 Enter；
Exit 只属于**真离场**（顶页抽走、浮层抽走）；无方向语义的对换用 Standard。

---

## 6. 页面转场模式学（问题 c）

### 6.1 关系表：方向判定的唯一入口

v1 的方向判定散在 AppRoot 两条 when 链里（S1）。v2 把「用什么转场」收敛为
**路由关系 → 模式 → 四方向规格** 的两级表，Motion.kt 独家装配：

```kotlin
enum class SlRelation { Chapter, Push, Form, Container }

@Composable
fun rememberSlPageMotion(): SlPageMotion
// SlPageMotion.enter/exit/popEnter/popExit(relation) —— 四模式 × 四方向
```

UI 层（迁移后）只剩**一张**「路由对 → SlRelation」的映射函数（比 v1 少一半 when），
转场规格零散写在 UI 层成为历史。`rememberSlPageMotion()` 内部读
`LocalDensity`（dp→px）与 `LocalReduceMotion`（降级），调用点不再自带 if（S2/P7）。

### 6.2 四种模式（几何与 v1 [不变]，装配权收拢）

**Chapter 换章**——同层互切（底部 4 签之间、签 ↔ 记一笔）：没有方向语义，不做横移。
进：淡入 250ms Enter + 8dp 微升；出：淡出 150ms Exit。减少动效：纯淡化（微升是装饰位移）。

**Push 层级压栈/回栈**（纸页对偶）——签 ↔ 层级页（详情/管理/设置）。
顶页从右 32dp 推进（缩 0.94→1、淡入，320ms Enter）；底页 24dp 退让 + 缩沉 0.94 +
变暗至 0.5（250ms Enter，**早 70ms 先收**——层次感来自时差）。
pop 镜像：顶页向右 32dp 抽走（250ms Exit）；底页归位回升
（**归位起点 α=0**，与让位终点 0.5 解耦——AnimatedContent 恒把进场页画在上层，
从 0.5 起步会隔面纱盖住正被抽走的顶页；v1 裁定原样保留）。

**Form 铺页**——全屏表单（记一笔）盖上来：新纸从下方 24dp 轻铺（320ms Enter）；
离场向下轻抽 + 淡出（150ms Exit）。下层只淡化不位移（表单不是推栈，是铺纸）。

**Container 容器变换对**——分区卡 ↔ 分区详情：**一纸一动**（下节）。

### 6.3 容器变换：一纸一动原则

> **红线：一次转场只有一个运动主角。页面层与共享元素同时运动 = 双运动，禁止。**

- 纸头（图标+分区名+金额）经 `sharedBounds` 从卡片矩形飞到页眉矩形（320ms Enter），
  是**唯一**的空间运动；
- 页面层只做短淡化（150ms Standard），不做任何位移/缩放——v1.5.6 裁定保留，
  v2 升格为规格红线，防止未来任何 PR 把页面层位移加回来；
- 纸头内容交叉淡化 150ms（飞行途中换装，不与边界抢戏）。

已知残留（P1 的尾巴，**待真机 A/B**）：页面层 150ms 静止后纸头独飞 170ms，
若观感「背景先死」，实验方向是把页面层淡化压到 100ms 或与纸头同长——
**只动时长，不加位移**。

### 6.4 predictive back

- targetSdk 37 强制开启；manifest `enableOnBackInvokedCallback="true"` 已就位；
- NavHost（navigation 2.10.1）经 SeekableTransitionState 把手势进度灌入 pop 转场，
  拖到哪走到哪，松手按剩余进度走完或回卷；
- **规格含义**：① pop 转场必须 tween（§4.1）；② **空间路径必须与 push 严格互逆**
  （拖拽返回 = 倒放压栈，v1 四段几何已满足）；③ 时间可以不对称
  （点按回退 250ms 求快；拖拽只消费空间路径，不消费时长）；
  ④ 底页归位 α 从 0 起步在拖拽下意味着「底页随手势逐渐显影」——
  语义正确（回卷的就是 push 的变暗）。

---

## 7. 微交互规格

| 项 | v1 | v2 | 依据 |
|---|---|---|---|
| 按压深度 | 0.975 | **Δ 0.96**（`SlFeel.PressScale`） | P3：小签上可感 |
| 按下 | 70ms tween Standard | [不变]（`slScene(Press, Standard)`） | 70ms 契约 |
| 抬起 | spring 0.85 / MediumLow | **Δ spring 0.8 / 整定 120ms**（`slState(Release, PressBounce)`） | 一次轻 settle，纸落回桌面 |
| 悬停拈起 | −1dp | **Δ −2dp**（`SlFeel.HoverLiftDp`） | P3；仅指针设备，触屏零影响 |
| 列表项增删 | fade 250 Enter / 150 Exit | fade [不变]；**Δ placement 弹簧**（`slState(Base)`） | P4：快速重排可中断续速 |
| 滚动入场 | 无（纪律） | [不变]（纪律 + `slAnimateItem` 只在数据变化时动） | 滚动零入场契约 |

不变项：`slPress` 只给**纸片类可按压面**，M3 控件已有涟漪不叠用；
`slPress` 必须与 `clickable(interactionSource = 同一个)` 配对；
触觉（长按 LongPress）与动效开关**无关**，不受 LocalReduceMotion 管。

---

## 8. 图表生长

- **语义**：数据图形用「生长」入场——柱从基线长起（柱底**严格贴基线**）、
  条从左端起跑线长出、环从 12 点顺时针画出。文字标注随生长显形，不单独飞入。
  生长是装饰：读屏语义由 speech 串独立承担（图变，语义不依赖动画）。
- **机制 [不变]**：单条线性时间线 `rememberSlChartTimeline()`（420ms + 30ms × 8 错峰
  = 660ms 总长）+ 纯函数 `chartGrow(timeline, index)`（逐元素 Enter 缓动映射）。
  时间线无协程 delay → 系统关动画时瞬时到 1，几何立即成形，天然完整降级。
- **Δ 一次生长**：进度经 `rememberSaveable` 留存。生长只在**第一次到达**时播放；
  导航离开再回来，图表以完整形态直接出现（P5）。中途离开（生长未完）则下次
  从零重放——不完整的显影不值得续播。数据更新（切月等）瞬时成形不重播：
  数据诚实 > 表演。
- `sweepClipPath` 功能与分配纪律原样保留（调用方 `remember` 复用 Path，本函数只 reset）。

---

## 9. 减少动效契约（问题 d）

两层降级**原样存续**（产品级需求，不是实现细节）：

1. **时长层（自动）**：所有自研动画走 Compose 动画原语（tween/spring/Animatable），
   系统 `ANIMATOR_DURATION_SCALE` 经 Compose UI 内部提供的 `MotionDurationScale`
   自动作用于每个规格（0 = 瞬时完成）。**禁止调用点换算时长**——与 Compose 内部
   机制重复，且 0.5x 慢速档下行为错误。弹簧同样被缩放（模拟时间缩放）。
2. **形变层（显式 `LocalReduceMotion`）**：时长缩放管不到装饰性形变
   （0ms 的缩放仍是缩放），由它显式分流：按压缩放归零（涟漪/色反馈仍在）、
   悬停拈起整体关闭、转场位移/缩放降级为纯淡化。

v2 的收敛（P7）：降级判断**内建**在新 API 里——`rememberSlPageMotion()`、
`slPress`、`slHoverLift` 内部读 `LocalReduceMotion`；迁移完成后调用点
**不再出现** `if (reduceMotion)`。图表时间线无需降级（§8 无 delay）。
M3 内置动画走 Compose 内部 MotionDurationScale，自动尊重系统设置，不重包。

**存续判据**（迁移后仍须成立）：关动画 → 页面切换瞬时完成、按压只剩涟漪与色反馈、
图表瞬时成形、滚动路径依旧零动画。

---

## 10. 令牌架构与 API

### 10.1 新体系（v2 真源，全部在 `ui/theme/Motion.kt`）

```
SlEasing   缓动四条：Enter / Exit / Standard / Linear          （§5）
SlTempo    节奏：Press 70 / Release 120 / Fast 150 / Base 250 /
           Slow 320 / ChartGrow 420 / ChartStagger 30 /
           ChartStaggerCap 8 / ChartTotal 660 / Spin 1920      （§4.4）
SlShift    位移三档：Micro 8dp / Standard 24dp / Page 32dp
SlFeel     形变量：PressScale 0.96 / PressBounce 0.8 /
           HoverLiftDp −2 / NavScaleSink 0.94 /
           NavAlphaUnder 0.5 / NavAlphaReturnStart 0

slScene<T>(ms, easing)     场景轨规格生成器 → TweenSpec（可 seek）
slState<T>(settleMs, damping)  状态轨规格生成器 → SpringSpec（可中断）

slPress(source) / slHoverLift() / slAnimateItem()   三修饰符（签名不变）
rememberSlChartTimeline() / chartGrow(timeline, index) / sweepClipPath(...)
SlRelation + SlPageMotion + rememberSlPageMotion()  页面转场装配（新）
```

### 10.2 兼容层（@Deprecated，迁移期存续）

旧 API 名**全部保留**、内部指向新体系（数值需要保真的除外，见下表备注）。
迁移完成后整体删除（§11）：

| v1 API | v2 去向 | 备注 |
|---|---|---|
| `SlMotion`（object） | `@Deprecated` → SlEasing/SlTempo/SlShift/SlFeel | |
| `PressDownMs / PressUpMs / FastMs / StandardMs / SlowMs` | `SlTempo.Press / Release / Fast / Base / Slow` | |
| `ChartGrowMs / ChartStaggerMs / ChartStaggerCap / ChartTotalMs` | `SlTempo.ChartGrow / …` | |
| `ShiftMicro / ShiftStandard / ShiftPage` | `SlShift.Micro / Standard / Page` | |
| `PressScale` | 字面量 0.975f 保真 | Δ 新值 0.96 只在新修饰符内生效；直接读旧令牌的点（现仅注释引用）迁移时换 `SlFeel.PressScale` |
| `HoverLiftDp` | 字面量 −1f 保真 | **SlipCard.kt L98 直读此令牌**：改指向会让未迁移文件观感突变，故 v2 冻结为 −1f，迁移时换 `SlFeel.HoverLiftDp`（−2） |
| `NavShiftIn / NavShiftUnder / NavEnterMs / NavExitMs / NavScaleSink / NavAlphaUnder / NavAlphaReturnStart` | `SlShift.Page / SlShift.Standard / SlTempo.Slow / SlTempo.Base / SlFeel.*` | |
| `PaperOut / PaperIn / Standard / Linear` | `SlEasing.Enter / Exit / Standard / Linear` | |
| `pressSpring()` | 字面量 spring(0.85, MediumLow) 保真 | 新代码用 `slState(Release, PressBounce)` |
| `slTween / slStandard / slFast` | `@Deprecated` → `slScene` | 签名与默认缓动逐字保留 |

**保真规则**：兼容层返回的数值与 v1 完全一致——迁移前应用的每个像素都和 v1 相同，
观感变化只来自 v2 内部实现的新令牌（按压缩放、抬起弹簧、悬停量、placement 弹簧、
图表一次生长），全部列真机验收（§12）。

---

## 11. 迁移指引（给后续队友）

按调用点族迁移，每族一个 PR，迁完即可在该 PR 内删对应兼容引用（兼容层本体
最后统一删）：

1. **AppRoot.kt**（最大单点）：删 6 组方向 lambda 与裸 tween 组合 →
   `val pageMotion = rememberSlPageMotion()` + 一张「路由对 → SlRelation」映射表；
   NavHost 四个转场参数改指 `pageMotion.enter/exit/popEnter/popExit(relation)`；
   IndexTab 五连 animate*AsState → `slState`（色/图标/高/边距全部状态轨弹簧）。
2. **SlipCard.kt**：自实现 hover 拈起（L97–106）合并进 `slHoverLift` 语义
   （拈起量换 `SlFeel.HoverLiftDp`；垫纸 alpha 保留在其文件内，规格 150 Enter）。
3. **SlSharedTransition.kt**：`slTween(FastMs, Standard)` → `slScene(Fast, Standard)`、
   `slTween(NavEnterMs, PaperOut)` → `slScene(Slow, Enter)`；顺带评估 pop 方向
   边界形变换 `Exit`/`Standard`（现 Enter，回程缓动未镜像，审计疑点 ⑥）。
4. **Charts.kt / StatsScreen.kt / LedgerRows.kt**：扇区带/图例底垫/行选中
   → `slState(Fast)`；时间线调用不变（一次生长已内建）。
5. **EntryEditHosts / EntryEditForm / LedgerDetailPane / LedgerListPane /
   SectionHomeScreen**：裸 `slTween/slStandard` → `slScene` 对应档
   （机械替换：`slStandard(PaperOut)` → `slScene(Base, Enter)` 等）。
6. **SyncStatusBadge.kt**：`SlowMs * 6` → `SlTempo.Spin`。
7. 全部迁完后：**删除** `@Deprecated object SlMotion` 与 `slTween/slStandard/slFast`，
   并顺手修 Accessibility.kt L24「MotionDurationScaleImpl」错述（S7）。

---

## 12. 验证与遗留风险

**机器验证（本任务已完成）**：`testDebugUnitTest` 382 全绿 · `lintRelease` 0 error ·
编译零新警告级问题（deprecation 警告为预期迁移信号）。

**真机验收项（迁移后统一走查，都是 Δ 项）**：

| # | 项 | 通过标准 | 不通过时的回调 |
|---|---|---|---|
| V1 | 按压 0.96 | 纸片按下清晰可感、不夸张 | 回 `SlFeel.PressScale` 0.97 |
| V2 | 抬起 settle（0.8/120ms） | 一次轻回弹、无果冻 | 阻尼升 0.85 |
| V3 | 悬停 2dp | 鼠标悬停可感、触屏无影响 | 回 1dp |
| V4 | placement 弹簧 | 快速增删/重排连续顺滑 | 回 tween 250 Enter |
| V5 | 图表一次生长 | 首进生长、返回即完整形态、切月瞬时 | 如需每次重播，去掉 saveable 即回 v1 行为 |
| V6 | predictive back 拖拽 | 分区详情拖拽返回跟手、空间路径 = push 倒放 | 检查是否被某分支降级成纯淡化 |
| V7 | 容器对「背景先死」 | 页面淡化与纸头飞行衔接自然 | 页面淡化 150→100ms 或与纸头同长（只动时长） |
| V8 | 两层降级回归 | 关动画全 app 瞬时、无位移残留 | 逐调用点查漏 |

**遗留风险**：

- R1 兼容层存活期内，直读旧令牌的点（SlipCard hover −1dp）与新修饰符（−2dp）
  并存，hover 量短暂不一致——迁移第 2 族时消除；
- R2 `rememberSlPageMotion()` 与 AppRoot 现行 lambda 并存期间，两套装配数值同源
  （§10.2 映射表），无行为分叉；迁移 PR 须整体切换，不允许半迁；
- R3 predict back 的 seek 手感依赖 navigation-compose 内部实现，升级 BOM 时
  须重验 V6；
- R4 图表一次生长依赖 NavHost 每条目的 SaveableStateHolder 行为，
  进程恢复路径（savedProgress=1）图表直接成形，符合预期但需在 V5 一并覆盖。

---

## 附录 A：全量审计清单（HEAD = 801e7f0）

| # | 位置 | 用途 | 现规格（v1 口径） | 疑点 |
|---|---|---|---|---|
| 1 | theme/Motion.kt（整文件） | 令牌源 + 生成器 + 修饰符 + 图表 | 70/120/150/250/320ms；位移 8/24/32dp；曲线三条 M3 | 头注「四档」实列 5 值（S7）；本任务重写 |
| 2 | AppRoot.kt:272–300 | chapterEnter/Exit 装配 + reduceMotion 捕获 | 淡入 250 Enter（+8dp 微升）/淡出 150 Exit | S2：降级散落；P6 |
| 3 | AppRoot.kt:302–349 | navPush/Pop Enter/Exit 四段 | 纸页对偶（320/250，位移 32/24dp，缩 0.94，α 0.5/0） | 几何自洽；装配收拢（§6） |
| 4 | AppRoot.kt:351–357 | containerEnter/Exit | 纯淡化 150 Standard | P1 残留：背景先死（V7） |
| 5 | AppRoot.kt:359–375 | formEnter/Exit | 320 Enter 铺入 / 150 Exit 抽走（24dp） | 无 |
| 6 | AppRoot.kt:387–404 | tabExit / tabPopEnter 方向 when 链 | 按对端路由定 Chapter/Container/Push | S1/P6：判定散落（双运动 bug 根因） |
| 7 | AppRoot.kt:417–420 | NavHost 默认转场 | 指向 3 的四段 | 无 |
| 8 | AppRoot.kt:699–723 | IndexTab 五连 animate*AsState（贴色/内容色/高/内边距/图标） | 150 Standard ×3、250 Standard ×2 | S4：tween 连点重启（P4） |
| 9 | AppRoot.kt:734 | slPress（索引贴） | 70 按下 / 弹 0.85 抬起，缩 0.975 | P3：量感弱 |
| 10 | SlSharedTransition.kt:40–54 | 纸头 sharedBounds | 边界 320 Enter；内容交叉 150 Standard | pop 也用 Enter（回程缓动未镜像，迁移时评估） |
| 11 | SectionHomeScreen.kt:322–331 | 纸片菜单 AnimatedVisibility | 250 Enter / 150 Exit，缩放 0.96 右上锚 | 无（生命周期三态处理正确） |
| 12 | SectionHomeScreen.kt:190 | slAnimateItem（分区卡） | 250/250/150 | 无 |
| 13 | SectionDetailScreen.kt:312 | slAnimateItem（账目行） | 同上 | 无 |
| 14 | LedgerListPane.kt:218–224 | 筛选摘要条 expand/shrink | 150 Enter/Exit 纵向 | 无 |
| 15 | LedgerListPane.kt:284 | slAnimateItem | 同 12 | 无 |
| 16 | LedgerRows.kt:232–236 | 行选中底垫 alpha | 150 Standard | 刻意 alpha-only（B′ 拍板避暗帧），保留 |
| 17 | LedgerRows.kt:249 | slHoverLift（流水行） | 1dp | P3 |
| 18 | LedgerDetailPane.kt:349–354 | 贴图放大层 | 250 Enter / 150 Exit 居中 0.96 | 无 |
| 19 | EntryEditForm.kt:414–419 | 贴图放大层 | 同 18 | 无 |
| 20 | EntryEditHosts.kt:116–127 | 记一笔宿主（居中/侧板） | 250 Enter / 150 Exit + 24dp 位移 | 无 |
| 21 | SlipCard.kt:97–106 | hover 拈起 + 垫纸 alpha（自实现） | 150 Standard ×2 | S6：与 slHoverLift 平行实现；P3 |
| 22 | SlipCard.kt:117 | slPress（纸片） | 同 9 | P3 |
| 23 | Charts.kt:189/300 | 环图时间线 + sweepClipPath 扫入 | 660ms 线性 + Enter 映射 | S5：重放（P5） |
| 24 | Charts.kt:228–234 | 扇区加粗带（逐片 animateFloatAsState） | 150 Standard | S4 |
| 25 | Charts.kt:513/572 | 条形图生长 | 同 23 | S5 |
| 26 | Charts.kt:662/728 | 日柱状图生长 | 同 23 | S5 |
| 27 | StatsScreen.kt:306–310 | 图例底垫 alpha | 150 Standard | 同 16 |
| 28 | SyncStatusBadge.kt:76–88 | 同步墨点无限旋转 | `SlowMs*6`=1920ms Linear | 档位语义误用（迁移 → `SlTempo.Spin`） |
| 29 | Accessibility.kt + Theme.kt:332–339 + MainActivity.kt:48 | LocalReduceMotion 契约与下发 | 两层降级 | S7：注释错述 MotionDurationScaleImpl |
| 30 | M3 内置（Sheet/Dialog/Menu/DatePicker/Snackbar/涟漪） | 内置动画 | Compose 内部 MotionDurationScale 自动 | 不重包（纪律） |
