package com.tzk1986.libretvaz.data.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 走查 #8（T8）：VocabStore 落盘改后台线程后，persist 读 index 与主线程 addWords 并发。
 * snapshot 须返回一致性拷贝（后续变更不回写快照），且各公开读写面加锁后并发压测不抛
 * ConcurrentModificationException / IndexOutOfBounds 等。
 */
class PinIndexSnapshotTest {

    @Test fun `snapshot 隔离后续变更`() {
        val pi = PinIndex()
        assertTrue(pi.ok) // ICU Collator 可用才有并发意义
        pi.add("流浪地球")
        pi.bump("流浪地球", 3)
        val (ws, wfs) = pi.snapshot()
        assertEquals(listOf("流浪地球"), ws)
        assertEquals(3L, wfs["流浪地球"])
        pi.add("流浪月球")
        pi.bump("流浪地球", 5)
        assertFalse(ws.contains("流浪月球"))
        assertEquals(3L, wfs["流浪地球"])
    }

    @Test fun `并发 add 与 snapshot 不抛`() {
        val pi = PinIndex(max = 50)
        assertTrue(pi.ok)
        val err = AtomicReference<Throwable>()
        val stop = AtomicBoolean(false)
        val writer = Thread {
            try { var i = 0; while (!stop.get()) pi.add("测试影片${i++}") }
            catch (e: Throwable) { err.set(e) } finally { stop.set(true) }
        }
        val reader = Thread {
            try { while (!stop.get()) pi.snapshot().first.size }
            catch (e: Throwable) { err.set(e) }
        }
        writer.start(); reader.start()
        Thread.sleep(300)
        stop.set(true)
        writer.join(3000); reader.join(3000)
        assertNull(err.get())
    }

    @Test fun `并发 add 与 suggest 不抛`() {
        val pi = PinIndex(max = 50)
        assertTrue(pi.ok)
        val err = AtomicReference<Throwable>()
        val stop = AtomicBoolean(false)
        val writer = Thread {
            try { var i = 0; while (!stop.get()) pi.add("阿宝${i++}") }
            catch (e: Throwable) { err.set(e) } finally { stop.set(true) }
        }
        val reader = Thread {
            try { while (!stop.get()) pi.suggest("ab", 10) }
            catch (e: Throwable) { err.set(e) }
        }
        writer.start(); reader.start()
        Thread.sleep(300)
        stop.set(true)
        writer.join(3000); reader.join(3000)
        assertNull(err.get())
    }
}
