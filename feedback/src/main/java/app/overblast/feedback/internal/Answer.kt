package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackResult

// THE WORKER'S ANSWER (contract §3.3) as a [FeedbackResult]. Every refusal carries a sentence in
// `error` meant for the reporter, and that sentence is used as it is; the defaults below are only for
// an answer that did not carry one (a proxy's HTML error page, an empty body).
internal object AnswerMapper {
  fun map(status: Int, body: String?): FeedbackResult {
    val json = body?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *>
    val error = (json?.get("error") as? String)?.takeIf { it.isNotBlank() }
    if (status in 200..299) {
      return FeedbackResult.Sent(
        id = json?.get("id") as? String,
        taskId = json?.get("taskId") as? String,
        conversationId = json?.get("conversationId") as? String,
        known = json?.get("known") == true,
      )
    }
    if (status == 507) {
      // `storage_full`: the task was filed without its files. Success, with something to say.
      return FeedbackResult.Sent(
        id = json?.get("id") as? String,
        taskId = json?.get("taskId") as? String,
        conversationId = json?.get("conversationId") as? String,
        notice = error ?: "Your report was sent, but the screenshot couldn't be stored.",
      )
    }
    val code = (json?.get("code") as? String)?.takeIf { it.isNotBlank() } ?: defaultCode(status)
    val retryAfter = (json?.get("retryAfter") as? Number)?.toLong()?.coerceAtLeast(0)
    return FeedbackResult.Failed(
      status = status,
      code = code,
      message = error ?: defaultSentence(code, status),
      field = json?.get("field") as? String,
      retryAfterSeconds = retryAfter,
    )
  }

  fun network(): FeedbackResult.Failed = FeedbackResult.Failed(
    status = 0,
    code = "network",
    message = "Couldn't reach the server. Check your connection and try again.",
  )

  fun rejected(e: PayloadRejected): FeedbackResult.Failed =
    FeedbackResult.Failed(status = if (e.code == "too_large") 413 else 400, code = e.code, message = e.sentence, field = e.field)

  private fun defaultCode(status: Int): String = when (status) {
    400 -> "invalid_field"
    401 -> "unknown_key"
    403 -> "key_disabled"
    413 -> "too_large"
    429 -> "rate_limited"
    500 -> "misconfigured"
    else -> "http_$status"
  }

  private fun defaultSentence(code: String, status: Int): String = when (code) {
    "invalid_field" -> "Something in this report couldn't be accepted."
    "unknown_key", "key_disabled", "origin_not_allowed" -> "Feedback isn't available in this app right now."
    "too_large" -> "This report is too large to send. Try without the screenshot."
    "rate_limited" -> "Too many reports for now. Please try again later."
    "category_not_accepted" -> "This app doesn't take this kind of report."
    "device_required" -> "This device couldn't be verified, so the report wasn't sent. Please try again later."
    "unknown_device", "device_signature_invalid" -> "This device couldn't be verified. Please try again."
    "misconfigured" -> "Feedback isn't working right now. Please try again later."
    else -> "The report couldn't be sent (error $status). Please try again later."
  }
}
