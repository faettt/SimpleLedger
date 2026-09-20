package com.simpleledger.app

import com.simpleledger.app.ui.hingeConstrainedWidth
import com.simpleledger.app.ui.hingeDetailInsetPx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 竖直铰链宽度夹取的纯函数单测。
 *
 * 这段几何逻辑（px 换算、坐标相减、双向夹取）是折叠屏适配里最容易出错的部分，而真折屏
 * 在本环境（无 adb 设备）不可运行验证，因此用纯函数单测把边界**数值地**钉死。
 *
 * 用例里的 px 值按 **density = 2.75**（典型 xxhdpi 折屏）从业务 dp 常量换算而来：
 * - 安全边距 `HINGE_SAFE_GAP = 8.dp` → 22.0px
 * - 最小可读宽度 `MIN_LIST_PANE_WIDTH = 280.dp` → 770.0px
 * - 普通窗口基础宽度 `400.dp` → 1100.0px
 * - 矮窗口基础宽度 `340.dp` → 935.0px
 *
 * 断言使用 `delta = 0f`（位精确）：输入输出均为整数算术的 float，不存在舍入误差。
 */
class HingeWidthTest {

    private companion object {
        // density 2.75 下的 dp→px 换算结果，全部为整数值
        const val GAP_PX = 22f          // 8.dp
        const val MIN_PX = 770f         // 280.dp
        const val BASE_NORMAL_PX = 1100f // 400.dp
        const val BASE_DENSE_PX = 935f   // 340.dp
        const val DELTA = 0f

        // 分隔线 1.dp → 2.75px（用于推算详情栏起点）
        const val DIVIDER_PX = 2.75f
    }

    @Test
    fun `hinge far to the right leaves base width untouched`() {
        // 可用 = 2000 − 200 − 22 = 1778px，远大于 base 1100px → 不受约束，返回 base
        assertEquals(
            1100f,
            hingeConstrainedWidth(
                hingeLeftPx = 2000,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )

        // 恰好在 base 边界上（可用 = 1100 == base）→ 仍返回 base，不多不少
        // 可用 = 1322 − 200 − 22 = 1100px
        assertEquals(
            1100f,
            hingeConstrainedWidth(
                hingeLeftPx = 1322,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )
    }

    @Test
    fun `hinge cutting through middle returns clamped available width`() {
        // 可用 = 1122 − 200 − 22 = 900px，落在 [770, 1100] 之间 → 精确返回可用宽度
        assertEquals(
            900f,
            hingeConstrainedWidth(
                hingeLeftPx = 1122,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )

        // 可用刚好等于下限 770px（= 992 − 200 − 22）→ 返回 770，边界不被吞掉
        assertEquals(
            770f,
            hingeConstrainedWidth(
                hingeLeftPx = 992,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )
    }

    @Test
    fun `hinge too close to the left returns min readable width`() {
        // 可用 = 722 − 200 − 22 = 500px < 下限 770px → 宁可压在铰链上也返回下限 770px
        assertEquals(
            770f,
            hingeConstrainedWidth(
                hingeLeftPx = 722,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )
    }

    @Test
    fun `degenerate hinge left of pane yields negative available and returns min`() {
        // 铰链在列表栏左侧：可用 = 100 − 200 − 22 = −122px（负）→ 仍返回下限 770px，
        // 不会因负值产生非法宽度（如 width=负数 导致布局崩溃或内容反向溢出）
        assertEquals(
            770f,
            hingeConstrainedWidth(
                hingeLeftPx = 100,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )

        // 更极端的退化：可用 = 0 − 4000 − 22 = −4022px，结论不变，仍是下限
        assertEquals(
            770f,
            hingeConstrainedWidth(
                hingeLeftPx = 0,
                paneLeftPx = 4000,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )
    }

    @Test
    fun `result depends only on available and base, not on hardcoded rail width`() {
        // 同一 hinge/pane 输入（可用 = 1222 − 200 − 22 = 1000px），仅切换 base：
        // 结果分别由 base 与可用宽度决定，与「Rail 宽度」这一外部常量无关。
        // 若实现里写死了某个 Rail 宽度（如 108dp→297px 或 84dp→231px），下面的期望值会随之改变。
        val availableLimited = 1000f // 可用宽度封顶值

        // 普通窗口 base 1100px > 可用 1000px → 被铰链封顶为 1000px
        assertEquals(
            availableLimited,
            hingeConstrainedWidth(
                hingeLeftPx = 1222,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_NORMAL_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )

        // 矮窗口 base 935px < 可用 1000px → 返回较小的 base 935px（铰链不再收紧）
        assertEquals(
            BASE_DENSE_PX,
            hingeConstrainedWidth(
                hingeLeftPx = 1222,
                paneLeftPx = 200,
                gapPx = GAP_PX,
                baseWidthPx = BASE_DENSE_PX,
                minWidthPx = MIN_PX,
            ),
            DELTA,
        )
    }

    // ---------------------------------------------------------------- 详情栏内容避让

    /**
     * 详情栏起点的真实口径（与 `LedgerScreen` 调用点一致）：
     * `detailPaneLeftPx = 列表栏左偏移 + 列表栏宽度 + 分隔线宽`。
     * 取展开态非矮窗口：Rail 84dp→231px、列表栏 400dp→1100px、分隔线 1dp→2.75px
     * ⇒ 详情栏起点 = 231 + 1100 + 2.75 = 1333.75px。
     */
    private val detailPaneLeftPx = 231f + BASE_NORMAL_PX + DIVIDER_PX

    @Test
    fun `detail pane overlapping hinge gets positive inset of exact width`() {
        // 详情栏起点 1333.75px，铰链右边界 1400px → 起点压在铰链上，补足差值 = 66.25px
        assertEquals(
            66.25f,
            hingeDetailInsetPx(
                hingeRightPx = 1400,
                detailPaneLeftPx = detailPaneLeftPx,
            ),
            DELTA,
        )

        // 铰链右边界更靠右（1560px）→ 内边距 = 1560 − 1333.75 = 226.25px
        assertEquals(
            226.25f,
            hingeDetailInsetPx(
                hingeRightPx = 1560,
                detailPaneLeftPx = detailPaneLeftPx,
            ),
            DELTA,
        )
    }

    @Test
    fun `detail pane starting exactly at hinge right edge gets zero inset`() {
        // 整数起点 1400px 与铰链右边界 1400px 精确相等 → 差值 0，内容已从边界之后开始，不多给 1px。
        assertEquals(
            0f,
            hingeDetailInsetPx(hingeRightPx = 1400, detailPaneLeftPx = 1400f),
            DELTA,
        )
        // 边界两侧各偏 1px，验证归零条件确为「起点 ≥ 右边界」：
        // 起点 1400 在右边界 1401 左侧 → 补 1px；起点 1401 在右边界 1400 右侧 → 0px
        assertEquals(
            1f,
            hingeDetailInsetPx(hingeRightPx = 1401, detailPaneLeftPx = 1400f),
            DELTA,
        )
        assertEquals(
            0f,
            hingeDetailInsetPx(hingeRightPx = 1400, detailPaneLeftPx = 1401f),
            DELTA,
        )
    }

    @Test
    fun `detail pane already right of hinge gets zero inset`() {
        // 铰链完全在详情栏左侧（右边界 1226px < 起点 1333.75px）→ 0，且不产生负值
        assertEquals(
            0f,
            hingeDetailInsetPx(
                hingeRightPx = 1226,
                detailPaneLeftPx = detailPaneLeftPx,
            ),
            DELTA,
        )
    }

    @Test
    fun `degenerate far-right pane start gets zero inset`() {
        // 极端退化：详情栏起点远在铰链右侧（4000px vs 右边界 1226px）→ 仍为 0
        assertEquals(
            0f,
            hingeDetailInsetPx(
                hingeRightPx = 1226,
                detailPaneLeftPx = 4000f,
            ),
            DELTA,
        )
    }
}