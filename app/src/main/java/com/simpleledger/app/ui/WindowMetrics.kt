package com.simpleledger.app.ui

import android.app.Activity
import android.graphics.Rect
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 折叠态（铰链）建模。
 *
 * 单靠窗口宽高无法区分「一块大屏」与「两块被铰链**物理分隔**的屏」：后者必须让用户
 * 把关键内容放回单侧区域，否则内容会正好压在折痕上。
 */
enum class FoldState {
    /** 无铰链，或铰链不影响布局（平板、普通手机、直立几乎不折的机型） */
    None,

    /** 竖直铰链：左右分屏（书本式内屏、双屏机竖握） */
    VerticalFold,

    /** 水平铰链：上下分屏（帐篷 / 桌面姿态、双屏机横握） */
    HorizontalFold,
}

/**
 * 折叠态 = 方向 + 位置。
 *
 * **为什么必须带位置**：「内容会不会压在折痕上」是几何问题，只知方向不够。「铰链在窗口
 * 正中」这一假设在居中铰链机型上碰巧成立，在**非居中铰链机型上直接判错**——这正是当初
 * 选真读 `FoldingFeature` 而非猜窗口中线的原因，那么位置也必须一并带出来，不能只留方向。
 */
data class FoldInfo(
    val state: FoldState = FoldState.None,
    /**
     * 铰链占据的**窗口坐标**矩形，单位为**像素**，原点为窗口左上角。
     * 无分隔铰链时为 null。之所以保留 px 原值：px→dp 需要 Density，而 Density 属于
     * 组合上下文，在 Flow 里拿不到；换算交由消费方（`LedgerScreen`）用 `LocalDensity` 完成。
     */
    val hingeBounds: Rect? = null,
)

/**
 * 读取当前窗口的折叠态（方向 + 铰链矩形）。
 *
 * `WindowInfoTracker` 只能通过 `Activity` 取窗口信息，因此用 `LocalActivity` 拿宿主。
 * 取不到 Activity 时（预览 / 部分测试宿主）直接返回 [FoldInfo] 默认值——折叠态是**增强**
 * 信息，缺失不应影响任何主流程，且 [FoldInfo] 默认 `state = None / hingeBounds = null`
 * 保证无折叠设备（普通手机 / 平板）行为与未引入本特性时完全一致。
 *
 * 首次组合即返回默认 [FoldInfo]，不等待 flow 首个值：`windowLayoutInfo` 在无折叠能力的
 * 设备上可能很晚才发值、甚至先发一份空值，若以「首个值」作为渲染前提，首帧就会被卡住。
 * 这里以 `FoldInfo()` 作为 `collectAsState` 的初值，任何时刻组合都有确定的值可用。
 */
@Composable
fun rememberFoldInfo(): FoldInfo {
    val activity = LocalActivity.current ?: return FoldInfo()
    val foldInfo by activity.foldInfoFlow().collectAsState(initial = FoldInfo())
    return foldInfo
}

/**
 * 把宿主 Activity 的窗口布局信息映射为折叠态 Flow。
 *
 * 注意 `WindowInfoTracker` 官方建议按 Activity 实例缓存单例，而非每次组合都新建——
 * 后者会在重组时反复注册回调。这里用 `remember(activity)` 绑定生命周期。
 */
@Composable
private fun Activity.foldInfoFlow(): Flow<FoldInfo> = remember(this) {
    WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).toFoldInfo()
}

/**
 * 从窗口布局信息里提取折叠态与铰链矩形。
 *
 * 仅取**第一个分隔型** `FoldingFeature`：非分隔的铰链（[FoldingFeature.isSeparating] 为
 * false，如设备近乎展平时的折痕）对布局没有影响，据此切分反而会把连贯的内容硬生生截断。
 *
 * `FoldingFeature.bounds` 与 `WindowInfoTracker` 同源，都是**窗口坐标**——消费方用
 * `positionInWindow()` 得到的偏移与它同处一个坐标系，可直接相减。
 */
private fun Flow<WindowLayoutInfo>.toFoldInfo(): Flow<FoldInfo> = map { info ->
    val feature = info.displayFeatures
        .filterIsInstance<FoldingFeature>()
        .firstOrNull { it.isSeparating }
    when (feature?.orientation) {
        FoldingFeature.Orientation.VERTICAL ->
            FoldInfo(FoldState.VerticalFold, feature.bounds)

        FoldingFeature.Orientation.HORIZONTAL ->
            FoldInfo(FoldState.HorizontalFold, feature.bounds)

        else -> FoldInfo()
    }
}

/**
 * 竖直铰链约束下的列表栏宽度（单位：像素）。
 *
 * 几何本质是一次双向夹取：
 * 1. 铰链左边界之前、扣除安全边距后可占用的宽度 `available = hingeLeftPx - paneLeftPx - gapPx`；
 * 2. 该可用宽度**不超过**基础宽度（否则栏位会无谓变宽）；
 * 3. 且**不低于**最小可读宽度——宁可让栏位压在铰链上，也不给一个窄到读不了的列表。
 *
 * 两侧边界与结果全部用像素表达，本函数只做纯算术，**不含任何 Android / Compose 依赖**，
 * 因此可在 JVM 单测里直接验证。这是刻意的：折叠屏几何里最容易错的就是 px/dp 换算、
 * 坐标原点与边界夹取，而实机（真折屏）在本环境不可用，纯函数单测是唯一能真正锁住它的手段。
 *
 * 坐标系前提：`FoldingFeature.bounds` 与 `Modifier.positionInWindow()` 同属**窗口坐标**
 * （原点为窗口左上角），故 [hingeLeftPx] 与 [paneLeftPx] 可直接相减。本函数**不假设**
 * 铰链居中，也不依赖 Navigation Rail 的宽度——Rail 宽度随 Expanded / dense 变化，写死必错。
 *
 * 边界说明：「列表栏左偏移尚未测得（为 0）」是 UI 生命周期状态，不是几何事实，因此
 * `paneLeftPx == 0` 时不做避让这一判断**保留在调用方**，不并入本纯函数。
 *
 * @param hingeLeftPx 铰链左边界的窗口坐标（px），与 `positionInWindow()` 同坐标系
 * @param paneLeftPx  列表栏左偏移的窗口坐标（px）
 * @param gapPx       列表栏与铰链之间保留的安全边距（px）
 * @param baseWidthPx 无铰链约束时的基础宽度（px）
 * @param minWidthPx  列表栏的最小可读宽度（px）
 * @return 受铰链约束后的列表栏宽度（px）
 */
fun hingeConstrainedWidth(
    hingeLeftPx: Int,
    paneLeftPx: Int,
    gapPx: Float,
    baseWidthPx: Float,
    minWidthPx: Float,
): Float {
    val availablePx = hingeLeftPx - paneLeftPx - gapPx
    return availablePx.coerceAtMost(baseWidthPx).coerceAtLeast(minWidthPx)
}

/**
 * 竖直铰链下，详情栏内容需要让出的左侧内边距（单位：像素）。
 *
 * [hingeConstrainedWidth] 只把**列表栏**夹到铰链左侧；其右侧的详情栏紧随 1dp 分隔线开始，
 * 起点往往仍落在铰链上（以展开态约 884dp 的机型为例：铰链中心在 ~442dp，列表栏 400dp +
 * 分隔线后详情栏自 ~401dp 起，必然横跨铰链）。若不给详情栏让位，其左侧的分类图标与文字
 * 标签会被折痕切成两半——而设计文档 §08 要求「内容不得跨越铰链」。
 *
 * 取舍：这里**只给内容让位，不挪栏位边界**。详情栏的**背景**跨过折痕无妨（用户看不出），
 * 但**内容**必须从铰链右边界之后开始。让两栏精确对齐铰链属于无折叠硬件时不可验证的复杂
 * 几何，风险高，故取最小且可构造验证的方案：详情栏起点若在铰链右边界之前，就补足这段距离。
 *
 * 本函数同样只做 px 算术、无任何 Android / Compose 依赖，可被 JVM 单测直接覆盖。
 *
 * @param hingeRightPx      铰链右边界的窗口坐标（px），与 `positionInWindow()` 同坐标系
 * @param detailPaneLeftPx  详情栏起点的窗口坐标（px）＝ 列表栏左偏移 ＋ 列表栏宽度 ＋ 分隔线宽
 * @return 应施加于详情栏起点的左侧内边距（px）；详情栏已在铰链右侧时为 0
 */
fun hingeDetailInsetPx(hingeRightPx: Int, detailPaneLeftPx: Float): Float {
    // 详情栏起点落在铰链右边界之前 → 补足差值；否则（含铰链完全在其左侧）为 0
    return (hingeRightPx - detailPaneLeftPx).coerceAtLeast(0f)
}