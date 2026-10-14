package app.overblast.feedback.okhttp

import app.overblast.feedback.OverblastFeedback
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Records each call's method, URL (without its query string), status and duration into the report's
 * `network` list. No headers, no bodies. A call that fails without an answer is recorded as status 0.
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(OverblastFeedbackInterceptor()).build()
 * ```
 */
public class OverblastFeedbackInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val started = System.nanoTime()
    val url = request.url.newBuilder().query(null).fragment(null).build().toString()
    try {
      val response = chain.proceed(request)
      OverblastFeedback.recordNetwork(request.method, url, response.code, elapsedMs(started))
      return response
    } catch (e: IOException) {
      OverblastFeedback.recordNetwork(request.method, url, 0, elapsedMs(started))
      throw e
    }
  }

  private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
