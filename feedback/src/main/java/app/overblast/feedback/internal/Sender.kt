package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackResult
import java.io.IOException

internal data class RetrySummary(val sent: Int, val dropped: Int, val kept: Int)

/**
 * Sends an encoded report to the door and turns the answer into a [FeedbackResult]. A 429 is kept in
 * the [PendingStore]; nothing here ever sends the same report twice in one call, except the one
 * resend device verification allows (§8.3: a device the worker does not know, or a key that requires
 * one).
 *
 * With [device], every report is signed at the moment it is sent, over the exact bytes sent. A report
 * kept for later is stored unsigned and signed when it finally goes, because a signature older than five
 * minutes is refused anyway.
 */
internal class FeedbackSender(
  private val endpoint: String,
  private val key: String,
  private val poster: HttpPoster,
  private val pending: PendingStore?,
  private val userAgent: String = "overblast-feedback-android",
  private val device: DeviceSigner? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  /** Whether the key registers devices (§2.1): with `verification: off`, reports go unsigned. */
  private val deviceGate: () -> Boolean = { true },
  /** Called on 403 `device_required`, which means the kept key settings are out of date. */
  private val onDeviceRequired: (() -> Unit)? = null,
) {
  private fun headers(): MutableMap<String, String> = linkedMapOf(
    "Content-Type" to "application/json; charset=utf-8",
    "Accept" to "application/json",
    "X-Overblast-Feedback-Key" to key,
    "User-Agent" to userAgent,
  )

  private class Attempt(val result: FeedbackResult, val signedWith: DeviceRecord?)

  /**
   * One report, signed when there is a device, and resent at most once after a device refusal.
   * [waitMs] is how long to wait for a registration still under way before sending unsigned.
   */
  fun post(body: String, waitMs: Long = SEND_WAIT_MS): FeedbackResult {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val signer = device ?: return attempt(bytes, null).result
    val first = attempt(bytes, if (deviceGate()) signer.ready(waitMs) else null)
    val failed = first.result as? FeedbackResult.Failed ?: return first.result
    return when (DeviceRetryPolicy.afterReport(failed.status, failed.code, signed = first.signedWith != null, resent = false)) {
      ReportRetry.NONE -> first.result
      ReportRetry.REREGISTER_AND_RESEND -> {
        first.signedWith?.let { signer.forget(it) }
        attempt(bytes, signer.ready(REGISTER_WAIT_MS)).result
      }
      ReportRetry.REGISTER_AND_RESEND -> {
        // The worker requires a device whatever the kept settings said: register now, and have them re-read.
        onDeviceRequired?.invoke()
        val fresh = signer.ready(REGISTER_WAIT_MS) ?: return first.result
        attempt(bytes, fresh).result
      }
    }
  }

  private fun attempt(bytes: ByteArray, record: DeviceRecord?): Attempt {
    val headers = headers()
    var signedWith: DeviceRecord? = null
    if (record != null && device != null) {
      val time = clock()
      val sig = device.sign(record, CanonicalString.build(record.deviceId, time, bytes).toByteArray(Charsets.UTF_8))
      if (sig != null) {
        headers[HEADER_DEVICE] = record.deviceId
        headers[HEADER_TIME] = time.toString()
        headers[HEADER_SIGNATURE] = Base64Url.encode(sig)
        signedWith = record
      }
    }
    val result = try {
      val answer = poster.post(endpoint, headers, bytes)
      AnswerMapper.map(answer.status, answer.body)
    } catch (e: IOException) {
      AnswerMapper.network()
    } catch (e: RuntimeException) {
      // A malformed endpoint URL, a SecurityException without the INTERNET permission: not a network
      // blip, so say what it was rather than "check your connection".
      FeedbackResult.Failed(0, "network", "The report couldn't be sent: ${e.javaClass.simpleName}.")
    }
    return Attempt(result, signedWith)
  }

  /** Sends [body]; a 429 is saved for later and comes back as [FeedbackResult.SavedForLater]. */
  fun send(body: String, waitMs: Long = SEND_WAIT_MS): FeedbackResult {
    val result = post(body, waitMs)
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
      // No wait for a registration under way: the drafts go at start, before anyone opened the sheet.
      when (val r = post(body, waitMs = 0)) {
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

  companion object {
    const val HEADER_DEVICE = "X-Overblast-Feedback-Device"
    const val HEADER_TIME = "X-Overblast-Feedback-Time"
    const val HEADER_SIGNATURE = "X-Overblast-Feedback-Signature"

    /** Send waits this much for a registration still under way, then sends unsigned. */
    const val SEND_WAIT_MS = 3_000L

    /** After a device refusal, the one resend waits this long for the registration it needs (a proof of work can take 20 s). */
    const val REGISTER_WAIT_MS = 25_000L
  }
}
