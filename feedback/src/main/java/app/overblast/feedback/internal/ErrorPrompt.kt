package app.overblast.feedback.internal

// THE ERROR PROMPT'S RULES (contract §3.6), as a state machine with an injected clock, so every rule is
// a JVM test. The Android half asks [evaluate] what to do and shows or hides the pill; it never decides.
//
//  - what counts: only the kinds the key lists, and only while the prompt is on (key AND app);
//  - when: at least 10 s after start; not while the person types (a focused text field or the keyboard),
//    the report sheet, the crash offer or any dialog is up, or the app is not in front; it waits up to
//    30 s for that to pass, then gives up;
//  - how often: once per process, once per `minHoursBetween` on the device, and after two closes without
//    a report, not for seven days; errors a second apart are one prompt.

/**
 * TYPING (§3.6): the keyboard is up, or a text field's text changed in the last five seconds. A field
 * that merely has focus (a screen that focuses its first field) does not hold the prompt back.
 */
internal object Typing {
  const val WINDOW_MS = 5_000L

  fun isTyping(now: Long, imeVisible: Boolean, lastTextChangeAt: Long?): Boolean =
    imeVisible || (lastTextChangeAt != null && now >= lastTextChangeAt && now - lastTextChangeAt < WINDOW_MS)
}

/** What survives the process: when it was last shown, the closes in a row, and a seven-day pause. */
internal data class PromptMemory(val lastShownAt: Long = 0, val closesInARow: Int = 0, val quietUntil: Long = 0)

internal interface PromptStore {
  fun load(): PromptMemory
  fun save(m: PromptMemory)
}

/** What is on screen when the prompt asks whether it may appear. */
internal data class PromptScene(
  val foreground: Boolean,
  val typing: Boolean,
  val sheetOrOfferOpen: Boolean,
  val dialogUp: Boolean,
)

internal sealed class PromptStep {
  object Show : PromptStep()

  /** Ask again in [ms]. */
  data class Wait(val ms: Long) : PromptStep()

  /** Nothing to show, or not now and not for this error. */
  object Drop : PromptStep()
}

internal class ErrorPromptGate(
  private val store: PromptStore,
  /** Wall-clock milliseconds, for the rules that outlive the process. */
  private val clock: () -> Long,
  /** When the library started, on the same clock. */
  private val startedAt: Long,
) {
  private var memory: PromptMemory? = null
  private var shownThisProcess = false
  private var pendingSince: Long? = null

  private fun mem(): PromptMemory = memory ?: runCatching { store.load() }.getOrDefault(PromptMemory()).also { memory = it }

  private fun remember(m: PromptMemory) {
    memory = m
    runCatching { store.save(m) }
  }

  /**
   * An error of [kind] happened. True when it starts a prompt the caller should now schedule; false
   * when it is not counted, is merged into one already waiting, or no prompt is possible.
   */
  @Synchronized
  fun onError(kind: String, config: ErrorPromptConfig, appAllows: Boolean): Boolean {
    if (!appAllows || !config.enabled || kind !in config.on) return false
    if (!allowedNow(config)) return false
    if (pendingSince != null) return false // merged into the one already waiting
    pendingSince = clock()
    return true
  }

  /** Whether the frequency rules allow a prompt at all right now. */
  private fun allowedNow(config: ErrorPromptConfig): Boolean {
    if (shownThisProcess) return false
    val now = clock()
    val m = mem()
    if (now < m.quietUntil) return false
    if (m.lastShownAt > 0 && now - m.lastShownAt < (config.minHoursBetween * HOUR).toLong()) return false
    return true
  }

  /** What to do now for the prompt waiting, given what is on screen. */
  @Synchronized
  fun evaluate(scene: PromptScene, config: ErrorPromptConfig, appAllows: Boolean): PromptStep {
    val since = pendingSince ?: return PromptStep.Drop
    if (!appAllows || !config.enabled || !allowedNow(config)) {
      pendingSince = null
      return PromptStep.Drop
    }
    val now = clock()
    val earliest = startedAt + START_DELAY_MS
    if (now < earliest) return PromptStep.Wait(earliest - now)
    // The 30 s of patience count from when the prompt could first have appeared.
    val deadline = maxOf(since, earliest) + PATIENCE_MS
    val blocked = !scene.foreground || scene.typing || scene.sheetOrOfferOpen || scene.dialogUp
    if (!blocked) return PromptStep.Show
    if (now >= deadline) {
      pendingSince = null
      return PromptStep.Drop
    }
    return PromptStep.Wait(minOf(POLL_MS, deadline - now))
  }

  /** The pill went up. */
  @Synchronized
  fun onShown() {
    shownThisProcess = true
    pendingSince = null
    remember(mem().copy(lastShownAt = clock()))
  }

  /** The person closed it without reporting. Two in a row pause it for seven days. */
  @Synchronized
  fun onClosed() {
    val m = mem()
    val closes = m.closesInARow + 1
    remember(if (closes >= 2) m.copy(closesInARow = 0, quietUntil = clock() + WEEK) else m.copy(closesInARow = closes))
  }

  /** The person reported from it: the closes no longer run in a row. */
  @Synchronized
  fun onReported() {
    remember(mem().copy(closesInARow = 0))
  }

  companion object {
    const val START_DELAY_MS = 10_000L
    const val PATIENCE_MS = 30_000L
    const val POLL_MS = 1_000L
    const val SHOW_MS = 8_000L
    const val HOUR = 3_600_000.0
    const val WEEK = 7L * 24 * 3_600_000L

    /**
     * Whether a request to [host] is the app's own (§3.6): one of [appHosts] or a subdomain of one.
     * With no hosts listed, no request counts, and the door's own host never does.
     */
    fun isAppHost(host: String?, appHosts: Collection<String>, doorHost: String?): Boolean {
      val h = host?.lowercase()?.trimEnd('.') ?: return false
      if (doorHost != null && h == doorHost.lowercase()) return false
      return appHosts.any { a ->
        val x = a.lowercase().trim().trimEnd('.')
        x.isNotEmpty() && (h == x || h.endsWith(".$x"))
      }
    }

    /** The kind a finished request counts as, or null: 5xx is `server_error`, no answer is `network`, 4xx nothing. */
    fun kindOfRequest(status: Int): String? = when {
      status == 0 -> ErrorPromptConfig.KIND_NETWORK
      status in 500..599 -> ErrorPromptConfig.KIND_SERVER_ERROR
      else -> null
    }
  }
}
