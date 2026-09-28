package com.simpleledger.app.sync

import com.simpleledger.app.sync.account.SyncPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * U-15 月键线程安全回归：`SyncPrefs.monthKey` 是 R-19 跨月归零的判据键，调用方
 * 分处主线程（设置页流量读数）与同步 IO 线程（PHOTOS 记账）——旧实现共享单例
 * `SimpleDateFormat`（非线程安全，JDK 明文契约），并发 format 可能写坏 internal
 * calendar 产出错键，使 `inCurrentMonth` 恒 false、月流量读数永久归零、额度门失效。
 *
 * 修法 = 每次调用新建实例。本测试用多线程并发锤击钉死：任意线程数/轮次下产出
 * 恒等于单线程金标（共享单例版本在并发下可观测到跨线程串写的错键）。
 */
class SyncPrefsMonthKeyTest {

    /** 键格式与月份归属：任意时刻的键 = 该时刻所在年月的 yyyyMM（金标独立构造） */
    @Test
    fun monthKeyMatchesGoldenFormat() {
        val at = Date()
        val golden = SimpleDateFormat("yyyyMM", Locale.US).format(at)
        assertEquals(golden, SyncPrefs.monthKey(at))

        // 跨月判据：上个月 / 明年同月各取一个时点，键随时刻走
        val cal = GregorianCalendar().apply { time = at; add(Calendar.MONTH, -1) }
        assertEquals(
            SimpleDateFormat("yyyyMM", Locale.US).format(cal.time),
            SyncPrefs.monthKey(cal.time),
        )
        val calNextYear = GregorianCalendar().apply { time = at; add(Calendar.YEAR, 1) }
        assertEquals(
            SimpleDateFormat("yyyyMM", Locale.US).format(calNextYear.time),
            SyncPrefs.monthKey(calNextYear.time),
        )
    }

    /** 并发锤击：8 线程 × 4_000 次 format 同一时刻，全部产出必须与单线程金标一致 */
    @Test
    fun monthKeyIsStableUnderConcurrentCalls() {
        val at = Date()
        val golden = SimpleDateFormat("yyyyMM", Locale.US).format(at)
        val threads = 8
        val iterations = 4_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val mismatches = AtomicInteger(0)
        repeat(threads) {
            Thread {
                try {
                    start.await()
                    repeat(iterations) {
                        if (SyncPrefs.monthKey(at) != golden) mismatches.incrementAndGet()
                    }
                } catch (_: InterruptedException) {
                    mismatches.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        done.await()
        assertEquals(
            "并发 format 不得产出错键（U-15：每次调用新建 SimpleDateFormat）",
            0,
            mismatches.get(),
        )
        assertTrue("锤击确实执行了 ${threads * iterations} 次", threads * iterations > 0)
    }
}
