package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackResult
import java.io.IOException

internal data class RetrySummary(val sent: Int, val dropped: Int, val kept: Int)

/**
 * Sends an encoded report to the door and turns the answer into a [FeedbackResult]. A 429 is kept in
 * the [PendingStore]; nothing here ever sends the same report twice in one call.
 */
internal class FeedbackSender(
  private val endpoint: String,
  private val key: String,
  private val poster: HttpPoster,
  private val pending: PendingStore?,
  private val userAgent: String = "overblast-feedback-android",
) {
  private fun headers(): Map<String, String> = linkedMapOf(
    "Content-Type" to "application/json; charset=utf-8",
    "Accept" to "application/json",
    "X-Overblast-Feedback-Key" to key,
    "User-Agent" to userAgent,
  )

  /** One attempt, no retry. */
  fun post(body: String): FeedbackResult = try {
    val answer = poster.post(endpoint, headers(), body.toByteArray(Charsets.UTF_8))
    AnswerMapper.map(answer.status, answer.body)
  } catch (e: IOException) {
    AnswerMapper.network()
  } catch (e: RuntimeException) {
    // A malformed endpoint URL, a SecurityException without the INTERNET permission: not a network
    // blip, so say what it was rather than "check your connection".
    FeedbackResult.Failed(0, "network", "The report couldn't be sent: ${e.javaClass.simpleName}.")
  }

  /** Sends [body]; a 429 is saved for later and comes back as [FeedbackResult.SavedForLater]. */
  fun send(body: String): FeedbackResult {
    val result = post(body)
    if (result is FeedbackResult.Failed && result.status == 429) {
      val saved = pending?.save(body) == true
      return if (saved) {
        FeedbackResult.SavedForLater(result.message, result.retryAfterSeconds)
      } else {
        result
      }
    }
    return result
  }

  /**
   * Each saved report, oldest first, gets one attempt. Sent and permanently refused ones (400, 401,
   * 403, 413) are removed; the first 429, 5xx or network failure stops the pass and keeps the rest.
   */
  @Synchronized
  fun retryPending(): RetrySummary {
    val store = pending ?: return RetrySummary(0, 0, 0)
    var sent = 0
    var dropped = 0
    val names = store.names()
    for ((i, name) in names.withIndex()) {
      val body = store.read(name)
      if (body == null) {
        store.delete(name)
        dropped++
        continue
      }
      when (val r = post(body)) {
        is FeedbackResult.Sent -> {
          store.delete(name)
          sent++
        }
        is FeedbackResult.Failed -> {
          if (r.status in 400..499 && r.status != 429 && r.status != 408) {
            store.delete(name)
            dropped++
          } else {
            return RetrySummary(sent, dropped, names.size - i)
          }
        }
        is FeedbackResult.SavedForLater -> return RetrySummary(sent, dropped, names.size - i)
      }
    }
    return RetrySummary(sent, dropped, 0)
  }
}
