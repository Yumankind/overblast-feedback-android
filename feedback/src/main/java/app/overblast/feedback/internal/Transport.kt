package app.overblast.feedback.internal

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpAnswer(val status: Int, val body: String?, val headers: Map<String, String> = emptyMap()) {
  /** A response header, by name in any case. */
  fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** One POST. An interface so the sender's answer handling is tested without a network. */
internal fun interface HttpPoster {
  @Throws(IOException::class)
  fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpAnswer
}

/** One GET, for the key's settings (§2.1). */
internal fun interface HttpGetter {
  @Throws(IOException::class)
  fun get(url: String, headers: Map<String, String>): HttpAnswer
}

/** `HttpURLConnection`, so the library brings no HTTP client of its own into the app. */
internal class UrlConnectionPoster(
  private val connectTimeoutMs: Int = 15_000,
  private val readTimeoutMs: Int = 60_000,
) : HttpPoster, HttpGetter {
  override fun get(url: String, headers: Map<String, String>): HttpAnswer {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "GET"
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = 15_000
      conn.useCaches = false
      conn.instanceFollowRedirects = false
      for ((k, v) in headers) conn.setRequestProperty(k, v)
      val status = conn.responseCode
      val stream = if (status >= 400) conn.errorStream else conn.inputStream
      val text = stream?.use { s -> s.readBytes().let { String(it.copyOf(minOf(it.size, 64 * 1024)), Charsets.UTF_8) } }
      val etag = conn.getHeaderField("ETag")
      return HttpAnswer(status, text, if (etag != null) mapOf("ETag" to etag) else emptyMap())
    } finally {
      conn.disconnect()
    }
  }

  override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpAnswer {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      conn.doOutput = true
      conn.useCaches = false
      conn.instanceFollowRedirects = false
      conn.setFixedLengthStreamingMode(body.size)
      for ((k, v) in headers) conn.setRequestProperty(k, v)
      conn.outputStream.use { it.write(body) }
      val status = conn.responseCode
      val stream = if (status >= 400) conn.errorStream else conn.inputStream
      val text = stream?.use { s -> s.readBytes().let { String(it.copyOf(minOf(it.size, 64 * 1024)), Charsets.UTF_8) } }
      return HttpAnswer(status, text)
    } finally {
      conn.disconnect()
    }
  }
}
