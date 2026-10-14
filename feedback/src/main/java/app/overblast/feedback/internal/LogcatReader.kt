package app.overblast.feedback.internal

// THE APP'S OWN LOGCAT. Since Android 4.1 an app can read only its own process's lines without
// READ_LOGS, and `--pid` (Android 7.0, our minSdk) asks logcat for exactly those. Best effort: a
// device whose logcat refuses, hangs or prints another format gives an empty list, never an error.
internal object LogcatReader {
  fun read(pid: Int, maxLines: Int = Limits.LOG_ENTRIES, timeoutMs: Long = 2_000): List<LogEntry> {
    var process: Process? = null
    return try {
      process = ProcessBuilder("logcat", "-d", "-t", maxLines.toString(), "--pid=$pid", "-v", "epoch")
        .redirectErrorStream(true)
        .start()
      val p = process
      var output = ""
      val reader = Thread {
        output = runCatching { p.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
      }
      reader.isDaemon = true
      reader.start()
      reader.join(timeoutMs)
      if (reader.isAlive) {
        p.destroy()
        reader.join(200)
      }
      // Only the tail is parsed: 500 lines of logcat are rarely past this, and a report carries 256 KB.
      LogcatParser.parse(if (output.length > 512_000) output.takeLast(512_000) else output, maxLines)
    } catch (e: Exception) {
      emptyList()
    } finally {
      runCatching { process?.destroy() }
    }
  }
}
