package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import java.io.File

// WHAT SURVIVES THE PROCESS (contract §3.4, §3.5): the log on disk, a session marker, and the record the
// uncaught-exception handler writes as the app dies. Plain java.io, so every rule runs on a JVM test
// with a temporary folder.
//
//   <root>/current/log-0.ndjson, log-1.ndjson   this session's log, two rotating files, 4 MB in all
//   <root>/current/session.json                 {"start": ms, "closed": bool}
//   <root>/current/crash.json                   the handler's record, when there was one
//   <root>/previous/...                         the same three, from a session that never closed,
//                                               kept until its report is offered

/**
 * The log's disk copy. Lines are appended as NDJSON with their sequence number; a collapsed line is
 * appended again with its new count, and reading keeps the last record of each number. Two files of
 * half the budget each: when the newer one fills, the older one is deleted and the newer one takes its
 * place, so the copy never holds more than the budget and always holds the newest lines.
 */
internal class LogDisk(private val dir: File, private val maxBytes: Long = Limits.DISK_BYTES) {
  private val older = File(dir, "log-0.ndjson")
  private val newer = File(dir, "log-1.ndjson")

  @Synchronized
  fun append(lines: List<SeqEntry>) {
    if (lines.isEmpty()) return
    if (!dir.exists() && !dir.mkdirs()) return
    val text = StringBuilder()
    for (l in lines) text.append(encode(l)).append('\n')
    val bytes = text.toString().toByteArray(Charsets.UTF_8)
    if (newer.exists() && newer.length() + bytes.size > maxBytes / 2) {
      older.delete()
      newer.renameTo(older)
    }
    java.io.FileOutputStream(newer, true).use { it.write(bytes) }
  }

  @Synchronized
  fun read(): List<LogEntry> {
    val bySeq = LinkedHashMap<Long, LogEntry>()
    for (f in listOf(older, newer)) {
      if (!f.exists()) continue
      val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
      for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        // A process killed mid-write leaves half a line: skipped, the rest still reads.
        val (seq, e) = decode(line) ?: continue
        bySeq[seq] = e
      }
    }
    return bySeq.entries.sortedBy { it.key }.map { it.value }
  }

  @Synchronized
  fun delete() {
    older.delete()
    newer.delete()
  }

  val bytes: Long get() = (if (older.exists()) older.length() else 0) + (if (newer.exists()) newer.length() else 0)

  companion object {
    fun encode(l: SeqEntry): String {
      val m = LinkedHashMap<String, Any?>()
      m["s"] = l.seq
      m.putAll(PayloadEncoder.logEntry(l.entry))
      return Json.encode(m)
    }

    fun decode(line: String): Pair<Long, LogEntry>? {
      val m = runCatching { Json.parse(line) }.getOrNull() as? Map<*, *> ?: return null
      val seq = (m["s"] as? Number)?.toLong() ?: return null
      val t = (m["t"] as? Number)?.toLong() ?: return null
      val level = LogLevel.values().firstOrNull { it.wire == m["level"] } ?: return null
      val msg = m["msg"] as? String ?: return null
      val repeat = (m["repeat"] as? Number)?.toInt() ?: 0
      return seq to LogEntry(t, level, msg, m["src"] as? String, repeat, (m["last"] as? Number)?.toLong())
    }
  }
}

internal data class SessionMarker(val start: Long, val closed: Boolean)

/** What the uncaught-exception handler wrote as the process died. */
internal data class HandlerRecord(val at: Long, val message: String, val stack: String)

/** What `ApplicationExitInfo` says about the last exit, reduced to what the decision needs. */
internal data class ExitRecord(val timestamp: Long, val reason: Int, val description: String?)

/** The previous session's unexpected end and its saved log, waiting for its offer. */
internal class PreviousSession(val crash: CrashInfo, val logs: List<LogEntry>)

internal class SessionFiles(private val root: File) {
  private val current = File(root, "current")
  private val previous = File(root, "previous")
  val log = LogDisk(current)

  /**
   * At start, before this session writes anything: a session whose marker never closed is moved aside
   * as `previous` (replacing an older one not yet offered), and anything else from the last run is
   * deleted. Returns the moved session's marker and handler record, for [CrashDecision].
   */
  @Synchronized
  fun rollOver(): Pair<SessionMarker, HandlerRecord?>? {
    val marker = readMarker(File(current, MARKER))
    val unclosed = marker != null && !marker.closed
    if (unclosed) {
      previous.deleteRecursively()
      if (!current.renameTo(previous)) {
        previous.mkdirs()
        current.listFiles()?.forEach { it.copyTo(File(previous, it.name), overwrite = true) }
        current.deleteRecursively()
      }
    } else {
      current.deleteRecursively()
    }
    current.mkdirs()
    if (!unclosed) return null
    return marker!! to readHandler(File(previous, CRASH))
  }

  /** Marks this session open (the app came to the foreground). */
  @Synchronized
  fun open(start: Long) = writeMarker(SessionMarker(start, closed = false))

  /** Marks this session closed (the app went to the background, or ended cleanly). */
  @Synchronized
  fun close(start: Long) = writeMarker(SessionMarker(start, closed = true))

  /** Called on the dying thread: small, synchronous, and never throws. */
  fun writeHandler(r: HandlerRecord) {
    runCatching {
      current.mkdirs()
      val f = File(current, CRASH)
      f.writeText(Json.encode(linkedMapOf("at" to r.at, "message" to r.message, "stack" to r.stack)), Charsets.UTF_8)
    }
  }

  /** The previous session's saved log, oldest first. */
  fun previousLogs(): List<LogEntry> = LogDisk(previous).read()

  @Synchronized
  fun discardPrevious() {
    previous.deleteRecursively()
  }

  private fun writeMarker(m: SessionMarker) {
    runCatching {
      current.mkdirs()
      val tmp = File(current, "$MARKER.tmp")
      tmp.writeText(Json.encode(linkedMapOf("start" to m.start, "closed" to m.closed)), Charsets.UTF_8)
      if (!tmp.renameTo(File(current, MARKER))) tmp.delete()
    }
  }

  private fun readMarker(f: File): SessionMarker? {
    val m = runCatching { Json.parse(f.readText(Charsets.UTF_8)) }.getOrNull() as? Map<*, *> ?: return null
    val start = (m["start"] as? Number)?.toLong() ?: return null
    return SessionMarker(start, m["closed"] == true)
  }

  private fun readHandler(f: File): HandlerRecord? {
    if (!f.exists()) return null
    val m = runCatching { Json.parse(f.readText(Charsets.UTF_8)) }.getOrNull() as? Map<*, *> ?: return null
    val at = (m["at"] as? Number)?.toLong() ?: return null
    return HandlerRecord(at, m["message"] as? String ?: "", m["stack"] as? String ?: "")
  }

  companion object {
    const val MARKER = "session.json"
    const val CRASH = "crash.json"
  }
}

/**
 * THE DECISION, as a table (§3.4): a session that closed is never offered; one that did not is
 * described by the handler's record when it wrote one (it has the message and the stack), then by
 * `ApplicationExitInfo`, then by the marker alone. Records older than the session are someone else's.
 */
internal object CrashDecision {
  // `ApplicationExitInfo.REASON_*`, as numbers, so this table runs on a JVM.
  const val REASON_LOW_MEMORY = 3
  const val REASON_CRASH = 4
  const val REASON_CRASH_NATIVE = 5
  const val REASON_ANR = 6
  const val REASON_USER_REQUESTED = 10
  const val REASON_USER_STOPPED = 11

  fun kindFor(reason: Int): String = when (reason) {
    REASON_CRASH, REASON_CRASH_NATIVE -> "crash"
    REASON_ANR -> "killed"
    REASON_LOW_MEMORY -> "oom"
    else -> "unknown"
  }

  /** The newest exit at or after the session's start, from a list newest first or not. */
  fun pickExit(exits: List<ExitRecord>, sessionStart: Long): ExitRecord? =
    exits.filter { it.timestamp >= sessionStart }.maxByOrNull { it.timestamp }

  fun decide(marker: SessionMarker?, handler: HandlerRecord?, exit: ExitRecord?): CrashInfo? {
    if (marker == null || marker.closed) return null
    val h = handler?.takeIf { it.at >= marker.start }
    val x = exit?.takeIf { it.timestamp >= marker.start }
    // The person (or a tool on their behalf) stopped the app: a force stop, a reset from settings. Not a crash.
    if (h == null && x != null && (x.reason == REASON_USER_REQUESTED || x.reason == REASON_USER_STOPPED)) return null
    return when {
      h != null -> CrashInfo(
        kind = "exception",
        at = h.at,
        message = h.message.ifBlank { x?.description },
        stack = h.stack.ifBlank { null },
        source = "handler",
      )
      x != null -> CrashInfo(kindFor(x.reason), x.timestamp, x.description?.ifBlank { null }, null, "exit_info")
      else -> CrashInfo("unknown", null, null, null, "marker")
    }
  }

  /** The handler's record of [e]: the message and stack redacted and cut to the contract's lengths. */
  fun record(e: Throwable, at: Long): HandlerRecord {
    val message = "${e.javaClass.name}${e.message?.let { ": $it" } ?: ""}"
    return HandlerRecord(
      at = at,
      message = Limits.clip(Redactor.redact(message), Limits.CRASH_MESSAGE),
      stack = Limits.clip(Redactor.redact(e.stackTraceToString()), Limits.CRASH_STACK),
    )
  }
}

/**
 * Writes the log's new lines to disk: at most every [debounceMs], and at once after an error line, so
 * the line that explains a crash is on disk before the crash. [flushNow] is also what the
 * uncaught-exception handler calls, synchronously, on the dying thread.
 */
internal class LogPersister(
  private val book: LogBook,
  private val disk: LogDisk,
  private val executor: java.util.concurrent.ScheduledExecutorService,
  private val debounceMs: Long = 5_000L,
) {
  private val lock = Any()
  private var scheduled: java.util.concurrent.ScheduledFuture<*>? = null
  private var scheduledAt = Long.MAX_VALUE

  fun attach() {
    book.onKept = { level -> onKept(level) }
  }

  fun onKept(level: LogLevel) {
    val delay = if (level == LogLevel.ERROR) 0L else debounceMs
    synchronized(lock) {
      val due = System.nanoTime() / 1_000_000 + delay
      val pending = scheduled
      if (pending != null && !pending.isDone && scheduledAt <= due) return
      pending?.cancel(false)
      scheduledAt = due
      scheduled = try {
        executor.schedule({ flushNow() }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
      } catch (e: RuntimeException) {
        null
      }
    }
  }

  /** Appends every change since the last write. Never throws. */
  fun flushNow() {
    synchronized(lock) {
      scheduledAt = Long.MAX_VALUE
    }
    runCatching { disk.append(book.drainChanges()) }
  }
}
