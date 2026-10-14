package app.overblast.feedback.internal

// WHAT NEVER LEAVES THE DEVICE IN A LOG LINE (contract §6): values that look like bearer tokens, API
// keys, JWTs, card numbers and `password=` / `token=` pairs become `[redacted]`. It runs over every log
// message and network URL before the report is built, so it errs towards cutting too much: a log line
// with a hole in it is still a log line, and a leaked key is not something a report can take back.
internal object Redactor {
  const val MASK = "[redacted]"

  private val BEARER = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
  private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
  // Secret-key shapes: OpenAI-style `sk-`, Overblast's own `ob_live_` API keys and `obfk_` feedback keys.
  private val KEYS = Regex("\\b(?:sk-[A-Za-z0-9_-]{12,}|ob_live_[A-Za-z0-9_-]{8,}|obfk_[A-Za-z0-9]{8,})")
  // `password=hunter2`, `"token": "abc"`, `api_key: abc`. The name and separator stay so the line still reads.
  private val PAIRS = Regex(
    "(?i)([\"']?\\b(?:password|passwd|pwd|passcode|secret|client_secret|token|access_token|refresh_token|id_token|auth_token|api[_-]?key|apikey|authorization)\\b[\"']?\\s*[=:]\\s*)" +
      // A value already handled by the bearer rule (`Authorization: Bearer [redacted]`) is left as it is.
      "(?!(?:bearer|basic)\\s|\\[redacted\\])" +
      "(\"[^\"]*\"|'[^']*'|[^\\s&,;\"'}]+)",
  )
  // 13 to 19 digits, optionally grouped by spaces or dashes, starting with 2-6 (every card network's
  // first digit). The leading digit keeps millisecond timestamps (which start with 1) out of it.
  private val CARD = Regex("(?<![\\d])[2-6](?:[ -]?\\d){12,18}(?![\\d])")

  fun redact(input: String): String {
    if (input.isEmpty()) return input
    var s = input
    s = BEARER.replace(s) { "${it.groupValues[1]} $MASK" }
    s = JWT.replace(s, MASK)
    s = KEYS.replace(s, MASK)
    s = PAIRS.replace(s) { m ->
      val v = m.groupValues[2]
      val quote = if (v.startsWith("\"")) "\"" else if (v.startsWith("'")) "'" else ""
      "${m.groupValues[1]}$quote$MASK$quote"
    }
    s = CARD.replace(s) { m ->
      val digits = m.value.filter { it.isDigit() }
      if (digits.length in 13..19 && luhn(digits)) MASK else m.value
    }
    return s
  }

  fun luhn(digits: String): Boolean {
    var sum = 0
    var double = false
    for (i in digits.length - 1 downTo 0) {
      var d = digits[i] - '0'
      if (d !in 0..9) return false
      if (double) {
        d *= 2
        if (d > 9) d -= 9
      }
      sum += d
      double = !double
    }
    return sum % 10 == 0
  }
}
