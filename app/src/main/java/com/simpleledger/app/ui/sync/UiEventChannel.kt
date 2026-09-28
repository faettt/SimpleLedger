package com.simpleledger.app.ui.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 一次性 UI 事件通道（U-18）。
 *
 * 缺陷背景：同步三页（同步设置 / 成员管理 / 冲突回收站）的一次性事件原先由
 * StateFlow（`UiState.event` 等）承载、纸签展示完才清空，两处破坏一次性语义：
 * 1. **同值事件被吞**——data class 相等的两次事件（如两次空跑的「同步完成」、
 *    连续两次测连成功）经 StateFlow conflated 合并，第二次永不展示（无任何纸签）；
 * 2. **回页重放**——纸签展示中离开页面，LaunchedEffect 协程被取消、清空动作
 *    不执行，事件残留在 VM 状态里，返回页面时旧事件再弹一次。
 *
 * 修法：事件改由 Channel 承载——每个事件是队列中的独立元素（同值不合并），
 * **接收即消费**（展示中途被取消也不再重放）；无人收集期间发出的事件先入队，
 * 收集者进来后按序全数送达（「立即同步」在页面外完成，回页仍能看到结果纸签）。
 *
 * 单消费者约定：每页只有一个收集者（页面 Composable 的 LaunchedEffect for 循环）；
 * 同一刻两个收集者会瓜分元素，接入时勿违反。
 */
class UiEventChannel<E> {

    /** 无界队列：事件节奏 = 用户动作（量级个位数），trySend 恒成功，绝不因队满吞事件 */
    private val channel = Channel<E>(capacity = Channel.UNLIMITED)

    /** 事件流（单消费者）：页面在 LaunchedEffect 里 `for (event in events)` 逐个消费 */
    val events: ReceiveChannel<E> = channel

    /** 发出一次性事件（不挂起；由 VM 在动作协程里调用） */
    fun send(event: E) {
        channel.trySend(event)
    }
}
