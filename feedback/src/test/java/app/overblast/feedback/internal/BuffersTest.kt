package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BuffersTest {
  @Test
  fun ringKeepsTheNewestInOrder() {
    val r = RingBuffer<Int>(3)
    (1..5).forEach { r.add(it) }
    assertEquals(listOf(3, 4, 5), r.snapshot())
  }

  @Test
  fun ringIsSafeAcrossThreads() {
    val r = RingBuffer<Int>(500)
    val pool = Executors.newFixedThreadPool(8)
    val latch = CountDownLatch(8)
    repeat(8) { t ->
      pool.execute {
        repeat(1000) { r.add(t * 1000 + it) }
        latch.countDown()
      }
    }
    assertTrue(latch.await(10, TimeUnit.SECONDS))
    pool.shutdown()
    assertEquals(500, r.size)
    assertEquals(500, r.snapshot().toSet().size)
  }

  @Test
  fun logRecorderKeepsFiveHundredRedactedLines() {
    var now = 0L
    val logs = LogRecorder(clock = { now++ })
    repeat(600) { logs.log(LogLevel.INFO, "Bearer abcdefghijkl $it") }
    val s = logs.snapshot()
    assertEquals(500, s.size)
    assertEquals("Bearer [redacted] 599", s.last().msg)
    assertEquals("app", s.last().src)
  }

  @Test
  fun networkRecorderDropsQueriesAndStartsAtTheRequest() {
    val n = NetworkRecorder(clock = { 1000L })
    n.record("GET", "https://api.example.com/p?q=secret#frag", 0, 300)
    val e = n.snapshot().single()
    assertEquals("https://api.example.com/p", e.url)
    assertEquals(700L, e.t)
    assertEquals(0, e.status)
  }
}
