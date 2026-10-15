package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel

// `logcat -v epoch` lines, which look like
//
//     1760440000.123  4123  4140 E OkHttp  : <-- HTTP FAILED: java.net.SocketTimeoutException
//
// seconds.fraction since the epoch, pid, tid, one-letter priority, tag, ": ", message. Banners
// (`--------- beginning of main`) and anything else that does not have that shape are skipped.
internal object LogcatParser {
  private val LINE = Regex("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFAS])\\s+(.*?)\\s*: ?(.*)$")

  /** The line redacted and cut, as a report carries it. */
  fun parseLine(line: String): LogEntry? =
    parseRaw(line)?.let { it.copy(msg = Limits.clip(Redactor.redact(it.msg), Limits.LOG_MSG)) }

  /** The line as logcat printed it, for the intake, which redacts only the lines it keeps (§3.5). */
  fun parseRaw(line: String): LogEntry? {
    val m = LINE.matchEntire(line.trimEnd('\r', '\n')) ?: return null
    val (secs, frac, _, _, prio, tag, msg) = m.destructured
    val millis = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    val t = (secs.toLongOrNull() ?: return null) * 1000L + millis
    val level = when (prio) {
      "V", "D" -> LogLevel.DEBUG
      "I" -> LogLevel.INFO
      "W" -> LogLevel.WARN
      else -> LogLevel.ERROR // E, F, A (assert); S is never printed
    }
    val text = if (tag.isEmpty()) msg else "$tag: $msg"
    return LogEntry(t, level, text, "logcat")
  }

  fun parse(output: String, max: Int = Limits.LOG_ENTRIES): List<LogEntry> =
    output.lineSequence().mapNotNull { parseLine(it) }.toList().takeLast(max)
}
