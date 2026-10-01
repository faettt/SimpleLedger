package com.simpleledger.app.util

import com.simpleledger.app.BuildConfig

/**
 * 统一日志收敛（全面审查 P2）：release 构建零 Logcat 输出，debug 构建保持既有
 * println 习惯（Android 上落 Logcat 的 System.out 通道，JVM 单测无副作用——
 * 不能直接用 android.util.Log：单测里它是 not mocked，见 SyncManager.deriveTimed）。
 *
 * 各同步子系统原来散落的 println 统一改走这里；排障时连真机 debug 包即可看到
 * 全部同步轨迹，release 用户的 Logcat 不再带任何内部信息。
 */
object SlLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) println("[$tag] $message")
    }

    /** debug 构建额外带栈（println 本身打不出栈，旧 printStackTrace 写 stderr 无门控） */
    fun d(tag: String, message: String, t: Throwable) {
        if (BuildConfig.DEBUG) println("[$tag] $message\n${t.stackTraceToString()}")
    }
}
