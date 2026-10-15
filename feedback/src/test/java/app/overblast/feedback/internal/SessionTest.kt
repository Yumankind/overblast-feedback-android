package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun e(seq: Long, msg: String, level: LogLevel = LogLevel.INFO, repeat: Int = 0) =
    SeqEntry(seq, LogEntry(seq * 1000, level, msg, "app", repeat, if (repeat > 1) seq * 1000 + 5 else null))

  // ── The disk copy ────────────────────────────────────────────────────────────────────────────

  @Test
  fun aRecountedLineReadsBackWithItsLatestCount() {
    val disk = LogDisk(tmp.newFolder())
    disk.append(listOf(e(1, "a"), e(2, "b")))
    disk.append(listOf(e(2, "b", repeat = 4), e(3, "c")))
    val back = disk.read()
    assertEquals(listOf("a", "b", "c"), back.map { it.msg })
    assertEquals(4, back[1].repeat)
    assertEquals(2005L, back[1].last)
  }

  @Test
  fun twoRotatingFilesKeepTheNewestWithinTheBudget() {
    val dir = tmp.newFolder()
    val disk = LogDisk(dir, maxBytes = 20_000)
    for (i in 1..400L) disk.append(listOf(e(i, "line $i " + "x".repeat(60))))
    assertTrue(disk.bytes <= 20_000)
    val back = disk.read()
    assertEquals("line 400", back.last().msg.substringBefore(" x"))
    assertTrue(back.size < 400)
    // The kept lines are the newest, without a hole.
    val nums = back.map { it.msg.split(' ')[1].toInt() }
    assertEquals((nums.first()..400).toList(), nums)
    assertEquals(setOf("log-0.ndjson", "log-1.ndjson"), dir.list()!!.toSet())
  }

  @Test
  fun halfALineFromAKilledProcessIsSkipped() {
    val dir = tmp.newFolder()
    val disk = LogDisk(dir)
    disk.append(listOf(e(1, "whole")))
    File(dir, "log-1.ndjson").appendText("""{"s":2,"t":2000,"level":"err""")
    assertEquals(listOf("whole"), disk.read().map { it.msg })
  }

  // ── The marker and the roll-over ─────────────────────────────────────────────────────────────

  @Test
  fun aClosedSessionIsForgottenAtTheNextStart() {
    val root = tmp.newFolder()
    val s1 = SessionFiles(root)
    s1.rollOver()
    s1.open(100)
    s1.log.append(listOf(e(1, "a")))
    s1.close(100)
    val s2 = SessionFiles(root)
    assertNull(s2.rollOver())
    assertTrue(s2.previousLogs().isEmpty())
    assertTrue(s2.log.read().isEmpty())
  }

  @Test
  fun anUnclosedSessionIsMovedAsideWithItsLogAndRecord() {
    val root = tmp.newFolder()
    val s1 = SessionFiles(root)
    s1.rollOver()
    s1.open(100)
    s1.log.append(listOf(e(1, "before the crash", LogLevel.ERROR)))
    s1.writeHandler(HandlerRecord(150, "java.lang.IllegalStateException: boom", "at x.y(Z.kt:1)"))
    val s2 = SessionFiles(root)
    val (marker, handler) = s2.rollOver()!!
    assertEquals(SessionMarker(100, closed = false), marker)
    assertEquals(HandlerRecord(150, "java.lang.IllegalStateException: boom", "at x.y(Z.kt:1)"), handler)
    assertEquals(listOf("before the crash"), s2.previousLogs().map { it.msg })
    // This session starts empty, and its own writes do not touch the previous one.
    assertTrue(s2.log.read().isEmpty())
    s2.log.append(listOf(e(1, "new")))
    assertEquals(listOf("before the crash"), s2.previousLogs().map { it.msg })
    s2.discardPrevious()
    assertTrue(s2.previousLogs().isEmpty())
  }

  @Test
  fun aSessionThatNeverCameToTheForegroundIsNoCrash() {
    val root = tmp.newFolder()
    val s1 = SessionFiles(root)
    s1.rollOver()
    s1.log.append(listOf(e(1, "a background start")))
    assertNull(SessionFiles(root).rollOver())
  }

  @Test
  fun aSecondUnexpectedEndReplacesOneNotYetOffered() {
    val root = tmp.newFolder()
    SessionFiles(root).apply { rollOver(); open(1); log.append(listOf(e(1, "first"))) }
    SessionFiles(root).apply { rollOver(); open(2); log.append(listOf(e(1, "second"))) }
    val s3 = SessionFiles(root)
    assertEquals(2L, s3.rollOver()!!.first.start)
    assertEquals(listOf("second"), s3.previousLogs().map { it.msg })
  }

  // ── The decision ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun exitReasonsMapToTheContractsKinds() {
    assertEquals("crash", CrashDecision.kindFor(4)) // REASON_CRASH
    assertEquals("crash", CrashDecision.kindFor(5)) // REASON_CRASH_NATIVE
    assertEquals("killed", CrashDecision.kindFor(6)) // REASON_ANR
    assertEquals("oom", CrashDecision.kindFor(3)) // REASON_LOW_MEMORY
    for (other in listOf(0, 1, 2, 7, 8, 9, 10, 11, 12, 13, 14)) assertEquals("unknown", CrashDecision.kindFor(other))
  }

  @Test
  fun aClosedOrMissingMarkerIsNeverOffered() {
    val h = HandlerRecord(150, "m", "s")
    val x = ExitRecord(160, 4, "crash")
    assertNull(CrashDecision.decide(null, h, x))
    assertNull(CrashDecision.decide(SessionMarker(100, closed = true), h, x))
  }

  @Test
  fun theHandlerWinsForMessageAndStack() {
    val c = CrashDecision.decide(SessionMarker(100, false), HandlerRecord(150, "java.lang.NullPointerException", "at a.b"), ExitRecord(160, 4, "crash"))!!
    assertEquals(CrashInfo("exception", 150, "java.lang.NullPointerException", "at a.b", "handler"), c)
  }

  @Test
  fun exitInfoAloneDescribesTheEnd() {
    assertEquals(CrashInfo("killed", 160, "user request after error: Input dispatching timed out", null, "exit_info"),
      CrashDecision.decide(SessionMarker(100, false), null, ExitRecord(160, 6, "user request after error: Input dispatching timed out")))
    assertEquals(CrashInfo("oom", 170, null, null, "exit_info"), CrashDecision.decide(SessionMarker(100, false), null, ExitRecord(170, 3, "")))
  }

  @Test
  fun theMarkerAloneIsAnUnknownEnd() {
    assertEquals(CrashInfo("unknown", null, null, null, "marker"), CrashDecision.decide(SessionMarker(100, false), null, null))
  }

  @Test
  fun recordsOlderThanTheSessionAreSomeoneElses() {
    val c = CrashDecision.decide(SessionMarker(100, false), HandlerRecord(50, "old", "old"), ExitRecord(40, 4, "old"))!!
    assertEquals("marker", c.source)
    assertEquals(ExitRecord(300, 6, "b"), CrashDecision.pickExit(listOf(ExitRecord(90, 4, "x"), ExitRecord(200, 4, "a"), ExitRecord(300, 6, "b")), 100))
    assertNull(CrashDecision.pickExit(listOf(ExitRecord(90, 4, "x")), 100))
  }

  @Test
  fun theHandlersRecordIsRedactedAndCut() {
    val e = IllegalStateException("token=abc123secret failed")
    val r = CrashDecision.record(e, 5)
    assertEquals("java.lang.IllegalStateException: token=[redacted] failed", r.message)
    assertTrue(r.stack.startsWith("java.lang.IllegalStateException: token=[redacted] failed"))
    assertFalse(r.stack.contains("abc123secret"))
    val deep = RuntimeException("x".repeat(5_000))
    val big = CrashDecision.record(deep, 5)
    assertEquals(Limits.CRASH_MESSAGE, big.message.length)
    assertTrue(big.stack.length <= Limits.CRASH_STACK)
  }

  // ── Writing the log to disk ──────────────────────────────────────────────────────────────────

  @Test
  fun anErrorIsOnDiskAtOnceAndChatterWaitsForTheDebounce() {
    val pool = Executors.newSingleThreadScheduledExecutor()
    try {
      val book = LogBook(trackChanges = true)
      val disk = LogDisk(tmp.newFolder())
      LogPersister(book, disk, pool, debounceMs = 800).attach()
      book.add(1_000, LogLevel.INFO, "chatter", "app")
      Thread.sleep(200)
      assertTrue(disk.read().isEmpty())
      book.add(2_000, LogLevel.ERROR, "the error", "app")
      val deadline = System.currentTimeMillis() + 2_000
      while (disk.read().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertEquals(listOf("chatter", "the error"), disk.read().map { it.msg })
    } finally {
      pool.shutdownNow()
      pool.awaitTermination(1, TimeUnit.SECONDS)
    }
  }

  @Test
  fun chatterReachesDiskAfterTheDebounce() {
    val pool = Executors.newSingleThreadScheduledExecutor()
    try {
      val book = LogBook(trackChanges = true)
      val disk = LogDisk(tmp.newFolder())
      LogPersister(book, disk, pool, debounceMs = 300).attach()
      for (i in 0 until 10) book.add(i * 1000L, LogLevel.INFO, "c$i", "app")
      val deadline = System.currentTimeMillis() + 3_000
      while (disk.read().size < 10 && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertEquals(10, disk.read().size)
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun theHandlersFlushIsSynchronous() {
    val book = LogBook(trackChanges = true)
    val disk = LogDisk(tmp.newFolder())
    val p = LogPersister(book, disk, Executors.newSingleThreadScheduledExecutor(), debounceMs = 60_000)
    p.attach()
    book.add(1, LogLevel.INFO, "last words", "app")
    p.flushNow()
    assertEquals(listOf("last words"), disk.read().map { it.msg })
  }

  // ── Logcat, followed ─────────────────────────────────────────────────────────────────────────

  @Test
  fun logcatLinesReachTheSinkUnredacted() {
    val got = java.util.concurrent.LinkedBlockingQueue<LogEntry>()
    val text = "1760440000.123  4123  4140 E OkHttp  : token=abc failed\\n--------- beginning of main\\n1760440001.000  4123  4140 I App     : ok\\n"
    val reader = LogcatReader(1, sink = { got.add(it) }, command = { listOf("sh", "-c", "printf '$text'") })
    reader.start()
    val first = got.poll(5, TimeUnit.SECONDS)!!
    val second = got.poll(5, TimeUnit.SECONDS)!!
    reader.stop()
    assertEquals(LogEntry(1760440000123, LogLevel.ERROR, "OkHttp: token=abc failed", "logcat"), first)
    assertEquals("App: ok", second.msg)
  }

  @Test
  fun aRestartedLogcatDoesNotRepeatLines() {
    val got = java.util.Collections.synchronizedList(ArrayList<LogEntry>())
    val text = "1760440000.123  1  1 I A : one\\n1760440001.000  1  1 I A : two\\n"
    val reader = LogcatReader(1, sink = { got.add(it) }, command = { listOf("sh", "-c", "printf '$text'") }, restartDelayMs = 20)
    reader.start()
    // The first run prints both lines, then the restart (after its pause) prints them again; neither repeats.
    Thread.sleep(500)
    reader.stop()
    assertEquals(listOf("A: one", "A: two"), got.map { it.msg })
  }
}
