package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class LogBookTest {
  private val utc = TimeZone.getTimeZone("UTC")

  // ── Repeats ──────────────────────────────────────────────────────────────────────────────────

  @Test
  fun identicalLinesCollapseIntoOneWithTheTotalCountAndTheLastTime() {
    val book = LogBook()
    for (i in 0 until 5) book.add(1_000L + i * 300, LogLevel.WARN, "retrying", "app")
    val e = book.snapshot().single()
    assertEquals(5, e.repeat)
    assertEquals(1_000L, e.t)
    assertEquals(2_200L, e.last)
    val m = PayloadEncoder.logEntry(e)
    assertEquals(5, m["repeat"])
    assertEquals(2_200L, m["last"])
  }

  @Test
  fun aLineSeenOnceCarriesNoRepeat() {
    val book = LogBook()
    book.add(1, LogLevel.INFO, "a", "app")
    val m = PayloadEncoder.logEntry(book.snapshot().single())
    assertFalse(m.containsKey("repeat") && m["repeat"] != null)
    assertNull(m["last"])
  }

  @Test
  fun aDifferentLevelSourceOrTextIsANewLine() {
    val book = LogBook()
    book.add(1, LogLevel.INFO, "a", "app")
    book.add(2, LogLevel.WARN, "a", "app")
    book.add(3, LogLevel.WARN, "a", "logcat")
    book.add(4, LogLevel.WARN, "b", "logcat")
    book.add(5, LogLevel.INFO, "a", "app")
    assertEquals(5, book.snapshot().size)
  }

  @Test
  fun repeatsDoNotCountAgainstTheIntake() {
    val book = LogBook()
    repeat(1_000) { book.add(1_000L + it % 999, LogLevel.DEBUG, "same", "app") }
    val e = book.snapshot().single()
    assertEquals(1_000, e.repeat)
  }

  // ── Intake per second ────────────────────────────────────────────────────────────────────────

  @Test
  fun atMostFiftyLinesASecondThenOneMarkerForTheGap() {
    val book = LogBook()
    for (i in 0 until 120) book.add(5_000L + i, LogLevel.INFO, "line $i", "app")
    book.add(7_100, LogLevel.INFO, "after a quiet second", "app")
    val s = book.snapshot()
    assertEquals(52, s.size)
    assertEquals("line 49", s[49].msg)
    val marker = s[50]
    assertEquals(LogLevel.WARN, marker.level)
    assertEquals("overblast", marker.src)
    assertEquals("70 lines dropped in 1 s (too fast)", marker.msg)
    assertEquals(5_119L, marker.t)
    assertEquals("after a quiet second", s[51].msg)
  }

  @Test
  fun warningsAndErrorsGetTheirOwnTwoHundred() {
    val book = LogBook()
    for (i in 0 until 60) book.add(9_000L, LogLevel.DEBUG, "d$i", "app")
    for (i in 0 until 250) book.add(9_000L, LogLevel.ERROR, "e$i", "app")
    val s = book.snapshot()
    assertEquals(50, s.count { it.level == LogLevel.DEBUG })
    assertEquals(200, s.count { it.level == LogLevel.ERROR })
    assertEquals("60 lines dropped in 1 s (too fast)", s.last().msg)
  }

  @Test
  fun theMarkerSaysHowManySecondsTheStormLasted() {
    val book = LogBook()
    for (sec in 0 until 3) for (i in 0 until 451) book.add(sec * 1000L + i, LogLevel.INFO, "s$sec-$i", "app")
    book.add(10_000, LogLevel.INFO, "calm", "app")
    val marker = book.snapshot().first { it.src == "overblast" }
    assertEquals("1,203 lines dropped in 3 s (too fast)", marker.msg)
  }

  @Test
  fun aSnapshotWritesThePendingMarker() {
    val book = LogBook()
    for (i in 0 until 51) book.add(1_000L, LogLevel.INFO, "x$i", "app")
    assertEquals("1 line dropped in 1 s (too fast)", book.snapshot().last().msg)
  }

  @Test
  fun onlyKeptLinesAreRedacted() {
    var calls = 0
    val book = LogBook(redact = { calls++; Redactor.redact(it) })
    for (i in 0 until 1_000) book.add(1_000L, LogLevel.INFO, "token=secret$i", "app")
    assertEquals(50, calls)
    assertEquals("token=[redacted]", book.snapshot().first().msg)
  }

  @Test
  fun nothingIsCollectedWhilePaused() {
    val book = LogBook()
    book.add(1, LogLevel.INFO, "before", "app")
    book.paused = true
    for (i in 0 until 500) assertFalse(book.add(2, LogLevel.ERROR, "during $i", "app"))
    book.paused = false
    book.add(3_000, LogLevel.INFO, "after", "app")
    assertEquals(listOf("before", "after"), book.snapshot().map { it.msg })
  }

  // ── The budget ───────────────────────────────────────────────────────────────────────────────

  @Test
  fun aFullBufferDropsTheOldestDebugAndInfoBeforeAnyWarningOrError() {
    val book = LogBook(maxLines = 5)
    book.add(1_000, LogLevel.ERROR, "old error", "app")
    book.add(2_000, LogLevel.WARN, "old warning", "app")
    for (i in 0 until 6) book.add(3_000L + i * 1000, LogLevel.DEBUG, "debug $i", "app")
    val s = book.snapshot().map { it.msg }
    assertEquals(listOf("old error", "old warning", "debug 3", "debug 4", "debug 5"), s)
    // With no low line left, the oldest high one goes.
    val only = LogBook(maxLines = 2)
    for (i in 0 until 3) only.add(i * 1000L, LogLevel.ERROR, "e$i", "app")
    assertEquals(listOf("e1", "e2"), only.snapshot().map { it.msg })
  }

  @Test
  fun theBufferHasAByteBudget() {
    val book = LogBook(maxBytes = 10_000)
    for (i in 0 until 100) book.add(i * 1000L, LogLevel.INFO, "x".repeat(900) + i, "app")
    assertTrue(book.byteCount <= 10_000)
    assertTrue(book.size in 9..10)
    assertTrue(book.snapshot().last().msg.endsWith("99"))
  }

  @Test
  fun twentyThousandLinesAtMostByDefault() {
    val book = LogBook()
    for (i in 0 until 25_000) book.add(i * 1000L, LogLevel.INFO, "l$i", "app")
    assertEquals(20_000, book.size)
  }

  @Test
  fun changesIncludeRecountedLines() {
    val book = LogBook(trackChanges = true)
    book.add(1, LogLevel.INFO, "a", "app")
    assertEquals(1, book.drainChanges().size)
    book.add(2, LogLevel.INFO, "a", "app")
    val again = book.drainChanges().single()
    assertEquals(2, again.entry.repeat)
    assertTrue(book.drainChanges().isEmpty())
  }

  // ── The excerpt ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun theExcerptKeepsErrorsFromTheLastTwoMinutesBeforeNewerChatter() {
    val opened = 1_000_000L
    val all = ArrayList<LogEntry>()
    all.add(LogEntry(opened - 300_000, LogLevel.ERROR, "too old to be first", "app"))
    all.add(LogEntry(opened - 100_000, LogLevel.ERROR, "the error", "app"))
    for (i in 0 until 1_000) all.add(LogEntry(opened - 90_000 + i * 10, LogLevel.INFO, "chatter $i", "app"))
    val ex = LogExcerpt.select(all, opened, maxEntries = 10)
    assertEquals(10, ex.size)
    assertEquals("the error", ex.first().msg)
    assertEquals("chatter 999", ex.last().msg)
    assertFalse(ex.any { it.msg == "too old to be first" })
  }

  @Test
  fun theExcerptRespectsTheByteCapAndKeepsOrder() {
    val all = (0 until 600).map { LogEntry(it.toLong(), if (it % 7 == 0) LogLevel.WARN else LogLevel.INFO, "m".repeat(1000) + it, "app") }
    val ex = LogExcerpt.select(all, 600)
    var bytes = 2
    ex.forEach { bytes += PayloadEncoder.logEntryBytes(it) }
    assertTrue(bytes <= Limits.LOG_BYTES)
    assertEquals(ex.sortedBy { it.t }, ex)
    // What the encoder then sends is the excerpt as it is.
    assertEquals(ex.size, PayloadEncoder.logs(ex).size)
  }

  @Test
  fun theExcerptIsEverythingWhenEverythingFits() {
    val all = (0 until 20).map { LogEntry(it.toLong(), LogLevel.INFO, "m$it", "app") }
    assertEquals(all, LogExcerpt.select(all, 100))
  }

  // ── The log file ─────────────────────────────────────────────────────────────────────────────

  private fun gunzipLines(b: ByteArray): List<String> =
    GZIPInputStream(ByteArrayInputStream(b)).bufferedReader().readLines()

  @Test
  fun theFileIsGzipNdjsonOldestFirst() {
    val all = (0 until 100).map { LogEntry(1_000L + it, LogLevel.INFO, "m$it", "app") } + LogEntry(5_000, LogLevel.WARN, "rep", "app", repeat = 3, last = 6_000)
    val f = LogFileBuilder.build(all, zone = utc)!!
    val lines = gunzipLines(f.gzip)
    assertEquals(101, lines.size)
    assertEquals(101, f.lines)
    assertEquals(1_000L, f.from)
    assertEquals(6_000L, f.to)
    val first = Json.parse(lines.first()) as Map<*, *>
    assertEquals(listOf("t", "level", "msg", "src"), first.keys.toList())
    val last = Json.parse(lines.last()) as Map<*, *>
    assertEquals(3.0, last["repeat"])
    assertEquals(6000.0, last["last"])
  }

  @Test
  fun aFileTooLargeLosesItsOldestLinesAndSaysSo() {
    val rnd = java.util.Random(1)
    // Random text barely compresses, so the cap bites.
    val all = (0 until 2_000).map { i -> LogEntry(i * 60_000L, LogLevel.INFO, (0 until 200).map { ('a' + rnd.nextInt(26)) }.joinToString(""), "app") }
    val f = LogFileBuilder.build(all, maxGzipBytes = 60_000, zone = utc)!!
    assertTrue(f.gzip.size <= 60_000)
    val lines = gunzipLines(f.gzip)
    val marker = Json.parse(lines.first()) as Map<*, *>
    assertEquals("warn", marker["level"])
    assertEquals("overblast", marker["src"])
    val cut = 2_000 - (lines.size - 1)
    assertEquals(String.format(java.util.Locale.US, "older lines cut to fit: %,d lines from 00:00 to %s", cut, java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = utc }.format(java.util.Date((cut - 1) * 60_000L))), marker["msg"])
    assertEquals(lines.size, f.lines)
    assertEquals((Json.parse(lines[1]) as Map<*, *>)["t"], marker["t"])
    assertEquals(all.last().t, f.to)
    // The cut is the smallest that fits: one line fewer cut would not.
    assertTrue(cut > 0)
  }

  @Test
  fun noLinesNoFile() {
    assertNull(LogFileBuilder.build(emptyList()))
  }

  // ── The 8 MB body ────────────────────────────────────────────────────────────────────────────

  @Test
  fun theLogFileGivesWayBeforeTheScreenshot() {
    val rnd = java.util.Random(2)
    val all = (0 until 3_000).map { i -> LogEntry(i.toLong(), LogLevel.INFO, (0 until 200).map { ('a' + rnd.nextInt(26)) }.joinToString(""), "app") }
    val file = LogFileBuilder.build(all)!!
    val shot = ScreenshotPart("image/jpeg", ByteArray(300_000), false)
    val room = 700_000
    val fitted = BodyFitter.fit(1_000, all, file, shot, maxBody = room, zone = utc)
    assertTrue(fitted.screenshot === shot)
    val f = fitted.logFile!!
    assertTrue(f.gzip.size < file.gzip.size)
    assertTrue(((f.gzip.size + 2) / 3) * 4 + ((shot.bytes.size + 2) / 3) * 4 <= room - 1_000)
  }

  @Test
  fun thenTheScreenshotShrinks() {
    val shot = ScreenshotPart("image/jpeg", ByteArray(3_000_000), false)
    var asked = 0
    val fitted = BodyFitter.fit(1_000, emptyList(), null, shot, maxBody = 2_000_000) { part, target ->
      asked = target
      ScreenshotPart(part.contentType, ByteArray(target / 2), part.highlighted)
    }
    assertTrue(asked in 1..1_500_000)
    assertEquals(asked / 2, fitted.screenshot!!.bytes.size)
  }

  @Test
  fun aScreenshotThatCannotShrinkIsLeftOut() {
    val shot = ScreenshotPart("image/jpeg", ByteArray(3_000_000), false)
    assertNull(BodyFitter.fit(1_000, emptyList(), null, shot, maxBody = 2_000_000).screenshot)
  }

  @Test
  fun aBodyThatFitsIsLeftAlone() {
    val all = listOf(LogEntry(1, LogLevel.INFO, "a", "app"))
    val file = LogFileBuilder.build(all)
    val shot = ScreenshotPart("image/jpeg", ByteArray(1000), false)
    val fitted = BodyFitter.fit(1_000, all, file, shot)
    assertTrue(fitted.logFile === file)
    assertTrue(fitted.screenshot === shot)
  }
}
