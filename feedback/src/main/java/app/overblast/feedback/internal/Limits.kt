package app.overblast.feedback.internal

// THE CONTRACT'S LIMITS (docs/feedback-reports.md §3 and §4 in the Overblast repository), in one
// place. Lengths are UTF-16 code units, which is what both a Kotlin `String.length` and the worker's
// JavaScript `.length` count, so a value cut here is never refused there.
internal object Limits {
  const val MESSAGE = 4000
  const val REPORTER_NAME = 120
  const val ACCOUNT_REF = 200
  const val EMAIL = 254

  const val SCREEN = 200
  const val TITLE = 200
  const val SHORT = 40 // appVersion, build, os, osVersion, locale, timeZone
  const val DEVICE = 80
  const val DEVICE_ID = 64
  const val EXTRA_ENTRIES = 20
  const val EXTRA_VALUE = 200
  // The contract bounds the values and the count, not the keys; a key is cut at the value's length so
  // that one runaway key cannot be the reason a report is refused.
  const val EXTRA_KEY = 200

  const val PATH_STEPS = 12
  const val PATH_STEP = 80
  const val ELEMENT_TYPE = 80
  const val ELEMENT_ID = 120
  const val ELEMENT_LABEL = 120

  // "5 MB decoded". Kept under 5,000,000 bytes rather than 5 MiB so it holds whichever the worker means.
  const val SCREENSHOT_BYTES = 5_000_000

  const val LOG_ENTRIES = 500
  const val LOG_MSG = 4000
  const val LOG_SRC = 40
  // "256 KB in all", measured here as the UTF-8 bytes of the encoded `logs` array, and kept a little
  // under 256,000 so neither reading of "KB" refuses it.
  const val LOG_BYTES = 250_000

  const val NETWORK_ENTRIES = 100
  const val NETWORK_URL = 500
  const val NETWORK_METHOD = 16

  /** Cuts [s] to at most [max] UTF-16 units without splitting a surrogate pair. */
  fun clip(s: String, max: Int): String {
    if (s.length <= max) return s
    var end = max
    if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
    return s.substring(0, end)
  }

  /** Trims, cuts, and turns blank into null: the shape of every optional string field. */
  fun opt(s: String?, max: Int): String? {
    val t = s?.trim() ?: return null
    if (t.isEmpty()) return null
    return clip(t, max)
  }

  private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

  fun isEmail(s: String): Boolean = s.length <= EMAIL && EMAIL_RE.matches(s)

  /** `[A-Za-z0-9_-]`, at most 64; anything else is dropped from the id rather than refused. */
  fun deviceId(s: String?): String? {
    val cleaned = s?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } ?: return null
    if (cleaned.isEmpty()) return null
    return cleaned.take(DEVICE_ID)
  }

  fun fraction(d: Double): Double {
    if (d.isNaN()) return 0.0
    val c = d.coerceIn(0.0, 1.0)
    return Math.round(c * 10_000.0) / 10_000.0
  }

  /** Query string and fragment removed, as the contract requires of every network entry. */
  fun stripQuery(url: String): String {
    val q = url.indexOfAny(charArrayOf('?', '#'))
    return if (q >= 0) url.substring(0, q) else url
  }
}
