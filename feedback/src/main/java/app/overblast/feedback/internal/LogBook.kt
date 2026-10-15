package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

// THE LOG, BOUNDED (contract §3.5). Every line, the app's own `log()` calls and its logcat alike, comes
// in through [LogBook.add], which decides in this order:
//
//  1. while the sheet is open nothing is collected (§3.4), so the sheet never fills its own report;
//  2. a line identical to the one before (level, source, text) is not stored again: the stored one
//     counts it (`repeat`) and keeps the time of the last one (`last`);
//  3. at most 50 lines a second enter, warnings and errors up to 200 a second; the rest are counted,
//     and one `overblast` warning marks the gap once a quiet second has passed;
//  4. only then is the line redacted and cut, so a storm of dropped lines costs a counter, not a regex;
//  5. the buffer holds 20,000 lines and 2 MB; when full, the oldest debug and info lines go before
//     any warning or error.
//
// Pure Kotlin, so every rule is pinned by a JVM test with a fake clock.

/** A kept line and its sequence number, for the disk copy, where a collapsed line is written again with its new count. */
internal class SeqEntry(val seq: Long, val entry: LogEntry)

internal class LogBook(
  private val maxLines: Int = Limits.BUFFER_LINES,
  private val maxBytes: Long = Limits.BUFFER_BYTES,
  private val perSecond: Int = Limits.INTAKE_PER_SECOND,
  private val urgentPerSecond: Int = Limits.INTAKE_URGENT_PER_SECOND,
  private val trackChanges: Boolean = false,
  private val redact: (String) -> String = Redactor::redact,
) {
  /** Called after a line is kept (outside the lock), with its level: the disk copy's cue to schedule a write. */
  @Volatile var onKept: ((LogLevel) -> Unit)? = null

  private class Line(val seq: Long, val t: Long, val level: LogLevel, val src: String, val msg: String, val bytes: Int) {
    var repeat = 1
    var last = t
    fun entry() = LogEntry(t, level, msg, src, if (repeat > 1) repeat else 0, if (repeat > 1) last else null)
  }

  private val lock = Any()
  // Two queues in arrival order, so eviction can take the oldest low-priority line without a scan.
  private val low = ArrayDeque<Line>()
  private val high = ArrayDeque<Line>()
  private var bytes = 0L
  private var seq = 0L

  private var lastLine: Line? = null
  private var lastRaw: String? = null

  private var windowSecond = Long.MIN_VALUE
  private var windowAll = 0
  private var windowUrgent = 0
  private var dropped = 0
  private var dropFirst = 0L
  private var dropLast = 0L

  private val changes = LinkedHashMap<Long, Line>()

  /** True while the report sheet is open: lines are ignored, not counted as dropped. */
  @Volatile var paused: Boolean = false

  /** Offers one line. False when it was ignored, collapsed into the line before, or dropped by the intake cap. */
  fun add(t: Long, level: LogLevel, raw: String, src: String): Boolean {
    if (paused) return false
    val kept: Boolean
    synchronized(lock) {
      kept = addLocked(t, level, raw, src)
    }
    if (kept) onKept?.invoke(level)
    return kept
  }

  private fun addLocked(t: Long, level: LogLevel, raw: String, src: String): Boolean {
    val prev = lastLine
    if (prev != null && dropped == 0 && prev.level == level && prev.src == src && lastRaw == raw) {
      prev.repeat++
      prev.last = maxOf(prev.last, t)
      if (trackChanges) changes[prev.seq] = prev
      return false
    }
    val second = Math.floorDiv(t, 1000L)
    if (second != windowSecond) {
      windowSecond = second
      windowAll = 0
      windowUrgent = 0
    }
    val urgent = level == LogLevel.WARN || level == LogLevel.ERROR
    val admitted = if (urgent) windowUrgent < urgentPerSecond else windowAll < perSecond
    if (!admitted) {
      if (dropped == 0) dropFirst = t
      dropped++
      dropLast = maxOf(dropLast, t)
      return false
    }
    if (urgent) windowUrgent++
    windowAll++
    // One marker per storm, not per second: it is written once a whole second has passed without a
    // drop (or when the buffer is read), so a gap of three seconds reads "... in 3 s".
    if (dropped > 0 && second > Math.floorDiv(dropLast, 1000L) + 1) flushDropsLocked()
    // Cut before redacting so a megabyte line costs no more than a long one, then cut to the limit.
    val text = Limits.clip(redact(Limits.clip(raw, Limits.LOG_MSG * 2)), Limits.LOG_MSG)
    store(t, level, Limits.clip(src, Limits.LOG_SRC), text)
    lastRaw = raw
    return true
  }

  private fun flushDropsLocked() {
    if (dropped == 0) return
    val seconds = Math.floorDiv(dropLast, 1000L) - Math.floorDiv(dropFirst, 1000L) + 1
    val msg = String.format(Locale.US, "%,d %s dropped in %d s (too fast)", dropped, if (dropped == 1) "line" else "lines", seconds)
    dropped = 0
    store(dropLast, LogLevel.WARN, SRC, msg)
    lastRaw = null // a marker is never collapsed into
  }

  private fun store(t: Long, level: LogLevel, src: String, msg: String) {
    val line = Line(++seq, t, level, src, msg, estimate(msg, src))
    if (line.level == LogLevel.WARN || line.level == LogLevel.ERROR) high.addLast(line) else low.addLast(line)
    bytes += line.bytes
    lastLine = line
    if (trackChanges) {
      changes[line.seq] = line
      // The disk writer has fallen far behind (or is not running): keep the newest changes only.
      while (changes.size > maxLines) changes.remove(changes.keys.first())
    }
    while ((low.size + high.size > maxLines || bytes > maxBytes) && low.size + high.size > 1) {
      val gone = if (low.isNotEmpty()) low.removeFirst() else high.removeFirst()
      bytes -= gone.bytes
      if (gone === lastLine) lastLine = null
    }
  }

  /** Everything the buffer holds, oldest first, with a pending drop marker written first. */
  fun snapshot(): List<LogEntry> = synchronized(lock) {
    flushDropsLocked()
    merged().map { it.entry() }
  }

  /** Lines kept or re-counted since the last call, in sequence order: what the disk copy appends. */
  fun drainChanges(): List<SeqEntry> = synchronized(lock) {
    flushDropsLocked()
    val out = changes.values.sortedBy { it.seq }.map { SeqEntry(it.seq, it.entry()) }
    changes.clear()
    out
  }

  val size: Int get() = synchronized(lock) { low.size + high.size }

  val byteCount: Long get() = synchronized(lock) { bytes }

  fun clear() = synchronized(lock) {
    low.clear()
    high.clear()
    bytes = 0
    lastLine = null
    lastRaw = null
    dropped = 0
    changes.clear()
  }

  private fun merged(): List<Line> {
    val out = ArrayList<Line>(low.size + high.size)
    var i = 0
    var j = 0
    val a = low.toList()
    val b = high.toList()
    while (i < a.size || j < b.size) {
      out.add(if (j >= b.size || (i < a.size && a[i].seq < b[j].seq)) a[i++] else b[j++])
    }
    return out
  }

  companion object {
    const val SRC = "overblast"

    /** The line's UTF-8 size plus a fixed allowance for its fields, close to what it costs serialized. */
    fun estimate(msg: String, src: String): Int = utf8Length(msg) + src.length + 64

    fun utf8Length(s: String): Int {
      var n = 0
      var i = 0
      while (i < s.length) {
        val c = s[i]
        n += when {
          c.code < 0x80 -> 1
          c.code < 0x800 -> 2
          Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
          else -> 3
        }
        i++
      }
      return n
    }
  }
}

/** `logs`, the excerpt (§3.5): every warning and error in the two minutes before the anchor, then the newest other lines, within 500 entries and 256 KB. */
internal object LogExcerpt {
  fun select(
    all: List<LogEntry>,
    anchor: Long,
    maxEntries: Int = Limits.LOG_ENTRIES,
    maxBytes: Int = Limits.LOG_BYTES,
    windowMs: Long = Limits.EXCERPT_WINDOW_MS,
  ): List<LogEntry> {
    if (all.isEmpty()) return emptyList()
    val chosen = BooleanArray(all.size)
    var count = 0
    var bytes = 2
    fun take(i: Int): Boolean {
      val size = PayloadEncoder.logEntryBytes(all[i])
      if (count >= maxEntries || bytes + size > maxBytes) return false
      chosen[i] = true
      count++
      bytes += size
      return true
    }
    for (i in all.indices.reversed()) {
      val e = all[i]
      val at = e.last ?: e.t
      val urgent = e.level == LogLevel.WARN || e.level == LogLevel.ERROR
      if (urgent && at <= anchor && at > anchor - windowMs) {
        if (count >= maxEntries) break
        take(i)
      }
    }
    for (i in all.indices.reversed()) {
      if (chosen[i]) continue
      if (count >= maxEntries) break
      take(i)
    }
    return all.filterIndexed { i, _ -> chosen[i] }
  }
}

/** `logFile` (§3.5): the whole buffer as gzip NDJSON; when it would not fit, the oldest lines are cut and the first line says so. */
internal object LogFileBuilder {
  fun build(all: List<LogEntry>, maxGzipBytes: Int = Limits.LOG_FILE_GZIP_BYTES, zone: TimeZone = TimeZone.getDefault()): LogFilePart? {
    if (all.isEmpty() || maxGzipBytes <= 0) return null
    val lines = all.map { Json.encode(PayloadEncoder.logEntry(it)) }
    gzip(lines, 0, null)?.takeIf { it.size <= maxGzipBytes }?.let { return LogFilePart(it, lines.size, all.first().t, all.last().let { e -> e.last ?: e.t }) }
    // The smallest cut that fits, by bisection over where the kept part starts.
    var lo = 1
    var hi = all.size - 1
    var best: Pair<Int, ByteArray>? = null
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val marker = cutMarker(all, mid, zone)
      val z = gzip(lines, mid, marker)
      if (z != null && z.size <= maxGzipBytes) {
        best = mid to z
        hi = mid - 1
      } else {
        lo = mid + 1
      }
    }
    val (start, bytes) = best ?: return null
    val last = all.last()
    return LogFilePart(bytes, all.size - start + 1, all[start].t, last.last ?: last.t)
  }

  private fun cutMarker(all: List<LogEntry>, start: Int, zone: TimeZone): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
    val msg = String.format(
      Locale.US, "older lines cut to fit: %,d lines from %s to %s",
      start, fmt.format(Date(all.first().t)), fmt.format(Date(all[start - 1].let { it.last ?: it.t })),
    )
    return Json.encode(PayloadEncoder.logEntry(LogEntry(all[start].t, LogLevel.WARN, msg, LogBook.SRC)))
  }

  private fun gzip(lines: List<String>, start: Int, first: String?): ByteArray? = try {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out, 64 * 1024).use { z ->
      if (first != null) {
        z.write(first.toByteArray(Charsets.UTF_8))
        z.write('\n'.code)
      }
      for (i in start until lines.size) {
        z.write(lines[i].toByteArray(Charsets.UTF_8))
        z.write('\n'.code)
      }
    }
    out.toByteArray()
  } catch (e: Exception) {
    null
  }
}

/**
 * Keeps the body under 8 MB (§3.5): the log file shrinks first (down to nothing), then the screenshot.
 * [baseBytes] is the encoded body without either; [shrinkShot] re-encodes the screenshot to at most
 * the given byte count, or returns null when it cannot.
 */
internal object BodyFitter {
  class Fitted(val logFile: LogFilePart?, val screenshot: ScreenshotPart?)

  private fun b64(n: Int): Long = ((n + 2L) / 3L) * 4L

  fun fit(
    baseBytes: Int,
    logLines: List<LogEntry>,
    logFile: LogFilePart?,
    screenshot: ScreenshotPart?,
    maxBody: Int = Limits.BODY_BYTES,
    zone: TimeZone = TimeZone.getDefault(),
    shrinkShot: (ScreenshotPart, Int) -> ScreenshotPart? = { _, _ -> null },
  ): Fitted {
    val room = maxBody.toLong() - baseBytes - 400 // the two fields' keys and their small siblings
    val shotSize = screenshot?.let { b64(it.bytes.size) } ?: 0L
    var file = logFile
    if (file != null && b64(file.gzip.size) + shotSize > room) {
      val left = room - shotSize
      // The base64 text of the file has to fit in what the screenshot leaves.
      file = if (left >= 4 * 1024) LogFileBuilder.build(logLines, (left / 4 * 3).toInt().coerceAtMost(Limits.LOG_FILE_GZIP_BYTES), zone) else null
      if (file != null && b64(file.gzip.size) + shotSize > room) file = null
    }
    var shot = screenshot
    val fileSize = file?.let { b64(it.gzip.size) } ?: 0L
    if (shot != null && fileSize + shotSize > room) {
      val target = ((room - fileSize) / 4 * 3).toInt()
      shot = if (target > 16 * 1024) shrinkShot(shot, target)?.takeIf { b64(it.bytes.size) + fileSize <= room } else null
    }
    return Fitted(file, shot)
  }
}
