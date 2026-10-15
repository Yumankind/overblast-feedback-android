package app.overblast.feedback.internal

import app.overblast.feedback.Category
import app.overblast.feedback.LogLevel
import app.overblast.feedback.Reporter

// THE BODY OF `POST /v1/feedback`, version 1. The field names in `PayloadEncoder.toMap` are the
// contract's, one for one; the test `PayloadEncoderTest` pins them, because the worker refuses an
// unknown top-level field by name.

internal data class ContextViewport(val w: Int, val h: Int, val dpr: Double)

internal data class ElementViewport(val w: Int, val h: Int)

internal data class FractionRect(val x: Double, val y: Double, val w: Double, val h: Double)

internal data class NativeElement(
  val path: List<String>,
  val type: String,
  val id: String?,
  val label: String?,
  val rect: FractionRect,
  val viewport: ElementViewport,
)

internal data class FeedbackContext(
  val screen: String? = null,
  val title: String? = null,
  val appVersion: String? = null,
  val build: String? = null,
  val os: String? = "Android",
  val osVersion: String? = null,
  val device: String? = null,
  val locale: String? = null,
  val timeZone: String? = null,
  val viewport: ContextViewport? = null,
  val sdkName: String = SDK_NAME,
  val sdkVersion: String = "0.0.0",
  val deviceId: String? = null,
  val extra: Map<String, String> = emptyMap(),
  /** Unix ms the reporter opened the report: the sheet appearing, or `report()` being called (§3.4). */
  val openedAt: Long? = null,
  /** `shake`, `button`, `api` or `relaunch` (§3.1). */
  val trigger: String? = null,
) {
  companion object {
    const val PLATFORM = "android"
    const val SDK_NAME = "overblast-feedback-android"
  }
}

internal data class ScreenshotPart(val contentType: String, val bytes: ByteArray, val highlighted: Boolean) {
  override fun equals(other: Any?): Boolean =
    other is ScreenshotPart && other.contentType == contentType && other.highlighted == highlighted && other.bytes.contentEquals(bytes)

  override fun hashCode(): Int = (contentType.hashCode() * 31 + bytes.contentHashCode()) * 31 + highlighted.hashCode()
}

/**
 * One log line. [repeat] is how many times the line occurred in a row when it was collapsed (§3.5),
 * 0 or 1 meaning once, and [last] the time of its last repeat.
 */
internal data class LogEntry(
  val t: Long,
  val level: LogLevel,
  val msg: String,
  val src: String?,
  val repeat: Int = 0,
  val last: Long? = null,
)

/** The previous session's unexpected end (§3.4). */
internal data class CrashInfo(
  val kind: String,
  val at: Long?,
  val message: String?,
  val stack: String?,
  val source: String,
)

/** `logFile` (§3.5): gzip-compressed NDJSON, oldest first. */
internal class LogFilePart(val gzip: ByteArray, val lines: Int, val from: Long, val to: Long)

internal data class NetworkEntry(val t: Long, val method: String, val url: String, val status: Int, val ms: Long)

internal data class FeedbackPayload(
  val message: String,
  val category: Category = Category.BUG,
  val reporter: Reporter? = null,
  val context: FeedbackContext = FeedbackContext(),
  val element: NativeElement? = null,
  val screenshot: ScreenshotPart? = null,
  val logs: List<LogEntry> = emptyList(),
  val network: List<NetworkEntry> = emptyList(),
  val crash: CrashInfo? = null,
  val logFile: LogFilePart? = null,
)

/** A field the library refuses before sending, in the worker's own shape: `invalid_field` or `too_large`. */
internal class PayloadRejected(val code: String, val field: String, val sentence: String) : Exception(sentence)

internal object PayloadEncoder {
  private val CONTENT_TYPES = setOf("image/png", "image/jpeg", "image/webp")

  /** The JSON text to send. Throws [PayloadRejected] on a field that cannot be cut to fit. */
  fun encode(p: FeedbackPayload): String = Json.encode(toMap(p))

  fun toMap(p: FeedbackPayload): Map<String, Any?> {
    val message = p.message.trim()
    if (message.isEmpty()) throw PayloadRejected("invalid_field", "message", "Please say what happened.")

    val out = LinkedHashMap<String, Any?>()
    out["v"] = 1
    out["message"] = Limits.clip(message, Limits.MESSAGE)
    out["category"] = p.category.wire
    out["reporter"] = reporter(p.reporter)
    out["context"] = context(p.context)
    out["crash"] = p.crash?.let { crash(it) }
    out["element"] = p.element?.let { element(it) }
    out["screenshot"] = p.screenshot?.let { screenshot(it) }
    out["logs"] = logs(p.logs).takeIf { it.isNotEmpty() }
    out["logFile"] = p.logFile?.let { logFile(it) }
    out["network"] = network(p.network).takeIf { it.isNotEmpty() }
    return out
  }

  private fun reporter(r: Reporter?): Map<String, Any?>? {
    if (r == null) return null
    val email = r.email?.trim()?.takeIf { it.isNotEmpty() }
    if (email != null && !Limits.isEmail(email)) {
      throw PayloadRejected("invalid_field", "reporter.email", "That email address doesn't look right.")
    }
    val m = linkedMapOf<String, Any?>(
      "email" to email,
      "name" to Limits.opt(r.name, Limits.REPORTER_NAME),
      "accountRef" to Limits.opt(r.accountRef, Limits.ACCOUNT_REF),
    )
    return if (m.values.all { it == null }) null else m
  }

  private fun context(c: FeedbackContext): Map<String, Any?> {
    val extra = LinkedHashMap<String, String>()
    for ((k, v) in c.extra) {
      if (extra.size >= Limits.EXTRA_ENTRIES) break
      val key = Limits.opt(k, Limits.EXTRA_KEY) ?: continue
      extra[key] = Limits.clip(v, Limits.EXTRA_VALUE)
    }
    return linkedMapOf(
      "platform" to FeedbackContext.PLATFORM,
      "screen" to Limits.opt(c.screen, Limits.SCREEN),
      "title" to Limits.opt(c.title, Limits.TITLE),
      "appVersion" to Limits.opt(c.appVersion, Limits.SHORT),
      "build" to Limits.opt(c.build, Limits.SHORT),
      "os" to Limits.opt(c.os, Limits.SHORT),
      "osVersion" to Limits.opt(c.osVersion, Limits.SHORT),
      "device" to Limits.opt(c.device, Limits.DEVICE),
      "locale" to Limits.opt(c.locale, Limits.SHORT),
      "timeZone" to Limits.opt(c.timeZone, Limits.SHORT),
      "viewport" to c.viewport?.let { linkedMapOf("w" to it.w.coerceAtLeast(0), "h" to it.h.coerceAtLeast(0), "dpr" to it.dpr) },
      "sdk" to linkedMapOf("name" to c.sdkName, "version" to c.sdkVersion),
      "deviceId" to Limits.deviceId(c.deviceId),
      "extra" to extra.takeIf { it.isNotEmpty() },
      "openedAt" to c.openedAt?.coerceAtLeast(0),
      "trigger" to c.trigger,
    )
  }

  private fun crash(c: CrashInfo): Map<String, Any?> = linkedMapOf(
    "kind" to c.kind,
    "at" to c.at?.takeIf { it > 0 },
    "message" to c.message?.let { Limits.opt(Redactor.redact(it), Limits.CRASH_MESSAGE) },
    "stack" to c.stack?.let { Limits.opt(Redactor.redact(it), Limits.CRASH_STACK) },
    "source" to c.source,
  )

  private fun logFile(f: LogFilePart): Map<String, Any?> = linkedMapOf(
    "contentType" to "application/x-ndjson",
    "encoding" to "gzip",
    "base64" to Base64Codec.encode(f.gzip),
    "lines" to f.lines,
    "from" to f.from,
    "to" to f.to,
  )

  fun element(e: NativeElement): Map<String, Any?> {
    val steps = e.path.mapNotNull { Limits.opt(it, Limits.PATH_STEP) }
    // The deepest steps say the most about what was tapped, so a long path keeps its tail.
    val path = if (steps.size > Limits.PATH_STEPS) steps.takeLast(Limits.PATH_STEPS) else steps
    val x = Limits.fraction(e.rect.x)
    val y = Limits.fraction(e.rect.y)
    val w = Limits.fraction(minOf(e.rect.w, 1.0 - x))
    val h = Limits.fraction(minOf(e.rect.h, 1.0 - y))
    return linkedMapOf(
      "kind" to "native",
      "path" to path,
      "type" to (Limits.opt(e.type, Limits.ELEMENT_TYPE) ?: "View"),
      "id" to Limits.opt(e.id, Limits.ELEMENT_ID),
      "label" to Limits.opt(e.label?.replace(Regex("\\s+"), " "), Limits.ELEMENT_LABEL),
      "rect" to linkedMapOf("x" to x, "y" to y, "w" to w, "h" to h),
      "viewport" to linkedMapOf("w" to e.viewport.w.coerceAtLeast(1), "h" to e.viewport.h.coerceAtLeast(1)),
    )
  }

  private fun screenshot(s: ScreenshotPart): Map<String, Any?> {
    if (s.contentType !in CONTENT_TYPES) throw PayloadRejected("invalid_field", "screenshot.contentType", "The screenshot is in a format that can't be sent.")
    if (s.bytes.size > Limits.SCREENSHOT_BYTES) throw PayloadRejected("too_large", "screenshot", "The screenshot is too large to send.")
    return linkedMapOf(
      "contentType" to s.contentType,
      "base64" to Base64Codec.encode(s.bytes),
      "highlighted" to s.highlighted,
    )
  }

  fun logEntry(l: LogEntry): Map<String, Any?> = linkedMapOf(
    "t" to l.t,
    "level" to l.level.wire,
    "msg" to Limits.clip(l.msg, Limits.LOG_MSG),
    "src" to Limits.opt(l.src, Limits.LOG_SRC),
    "repeat" to l.repeat.takeIf { it > 1 },
    "last" to l.last.takeIf { l.repeat > 1 },
  )

  /** The UTF-8 size of one entry in the `logs` array, its comma included. */
  fun logEntryBytes(l: LogEntry): Int = Json.encode(logEntry(l)).toByteArray(Charsets.UTF_8).size + 1

  /** The newest entries that fit both caps: at most 500, and at most [Limits.LOG_BYTES] encoded. */
  fun logs(all: List<LogEntry>): List<Map<String, Any?>> {
    val newestFirst = all.sortedBy { it.t }.takeLast(Limits.LOG_ENTRIES).asReversed()
    val kept = ArrayList<Map<String, Any?>>()
    var bytes = 2 // the brackets
    for (l in newestFirst) {
      val m = logEntry(l)
      val size = Json.encode(m).toByteArray(Charsets.UTF_8).size + 1 // and its comma
      if (bytes + size > Limits.LOG_BYTES) break
      bytes += size
      kept.add(m)
    }
    kept.reverse()
    return kept
  }

  fun network(all: List<NetworkEntry>): List<Map<String, Any?>> =
    all.sortedBy { it.t }.takeLast(Limits.NETWORK_ENTRIES).map {
      linkedMapOf(
        "t" to it.t,
        "method" to Limits.clip(it.method.trim().uppercase().ifEmpty { "GET" }, Limits.NETWORK_METHOD),
        "url" to Limits.clip(Redactor.redact(Limits.stripQuery(it.url.trim())), Limits.NETWORK_URL),
        "status" to it.status.coerceIn(0, 999),
        "ms" to it.ms.coerceAtLeast(0),
      )
    }
}

/** RFC 4648 base64 with padding, written here so the encoder is the same on a device and on a JVM. */
internal object Base64Codec {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  fun encode(data: ByteArray): String {
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < data.size) {
      val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8) or (data[i + 2].toInt() and 0xff)
      out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
      i += 3
    }
    val rest = data.size - i
    if (rest == 1) {
      val n = data[i].toInt() and 0xff shl 16
      out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append("==")
    } else if (rest == 2) {
      val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8)
      out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append(ALPHABET[n ushr 6 and 63]).append('=')
    }
    return out.toString()
  }
}
