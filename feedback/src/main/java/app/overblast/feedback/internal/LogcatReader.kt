package app.overblast.feedback.internal

// THE APP'S OWN LOGCAT, AS IT HAPPENS. Since Android 4.1 an app can read only its own process's lines
// without READ_LOGS, and `--pid` (Android 7.0, our minSdk) asks logcat for exactly those. One `logcat`
// process follows the log for the life of the app and hands every line to the same intake as the app's
// own `log()` calls (§3.5), so a logcat storm is capped and collapsed like any other, and the lines are
// on disk if the app crashes. Best effort: a device whose logcat refuses or prints another format gives
// no lines, never an error.
internal class LogcatReader(
  private val pid: Int,
  private val sink: (LogEntry) -> Unit,
  private val command: (Int) -> List<String> = { listOf("logcat", "-v", "epoch", "--pid=$it") },
  private val restartDelayMs: Long = 5_000L,
) {
  @Volatile private var running = false
  @Volatile private var process: Process? = null
  private var thread: Thread? = null

  @Synchronized
  fun start() {
    if (running) return
    running = true
    thread = Thread({ loop() }, "overblast-feedback-logcat").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
      start()
    }
  }

  @Synchronized
  fun stop() {
    running = false
    runCatching { process?.destroy() }
  }

  private fun loop() {
    // A restarted logcat prints its buffer again; lines at or before the last one seen are skipped.
    var lastSeen = Long.MIN_VALUE
    var skipThrough = Long.MIN_VALUE
    var restarts = 0
    while (running && restarts <= MAX_RESTARTS) {
      try {
        val p = ProcessBuilder(command(pid)).redirectErrorStream(true).start()
        process = p
        p.inputStream.bufferedReader().use { reader ->
          while (running) {
            val line = reader.readLine() ?: break
            val e = LogcatParser.parseRaw(line) ?: continue
            if (e.t <= skipThrough) continue
            lastSeen = e.t
            sink(e)
          }
        }
        runCatching { p.destroy() }
      } catch (e: InterruptedException) {
        return
      } catch (e: Exception) {
        // No logcat on this device, or not for us: nothing to read.
      }
      skipThrough = lastSeen
      restarts++
      if (running) runCatching { Thread.sleep(restartDelayMs) }
    }
  }

  private companion object {
    const val MAX_RESTARTS = 5
  }
}
