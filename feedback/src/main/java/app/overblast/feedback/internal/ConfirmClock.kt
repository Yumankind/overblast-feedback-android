package app.overblast.feedback.internal

// HOW LONG THE "SENT" CONFIRMATION STAYS, and when it closes by itself (pure, JVM-tested). It is timed
// to what it says: about two seconds for "Sent, thank you", about four for the longer known-issue
// sentence (and for a notice or a saved-for-later line). While TalkBack's focus is on its Done button
// the clock stops, so a person listening is never cut off; when focus leaves, the rest of the time runs,
// and never less than [MIN_AFTER_FOCUS_MS]. Done, Back, the handle and a tap outside close it at once.
internal class ConfirmClock(val holdMs: Long, startedAt: Long) {
  enum class Kind { THANKS, LONG }

  private var deadline = startedAt + holdMs
  private var pausedRemaining: Long? = null
  var closed = false
    private set

  val paused: Boolean get() = pausedRemaining != null

  /** TalkBack's focus came to Done: stop the clock. */
  fun focusIn(now: Long) {
    if (closed || paused) return
    pausedRemaining = (deadline - now).coerceAtLeast(0)
  }

  /** TalkBack's focus left Done: run what was left, at least [MIN_AFTER_FOCUS_MS]. */
  fun focusOut(now: Long) {
    val left = pausedRemaining ?: return
    pausedRemaining = null
    deadline = now + maxOf(left, MIN_AFTER_FOCUS_MS)
  }

  /** How long until it closes by itself, or null while paused or once closed. */
  fun remaining(now: Long): Long? = if (closed || paused) null else (deadline - now).coerceAtLeast(0)

  /** Whether it should close by itself now. */
  fun due(now: Long): Boolean = !closed && !paused && now >= deadline

  /** Closed by the person (Done, Back, the handle, outside) or by the clock; only the first counts. */
  fun close(): Boolean {
    if (closed) return false
    closed = true
    return true
  }

  companion object {
    const val THANKS_MS = 2_000L
    const val LONG_MS = 4_000L
    const val MIN_AFTER_FOCUS_MS = 1_500L

    fun holdFor(kind: Kind): Long = if (kind == Kind.THANKS) THANKS_MS else LONG_MS
  }
}
