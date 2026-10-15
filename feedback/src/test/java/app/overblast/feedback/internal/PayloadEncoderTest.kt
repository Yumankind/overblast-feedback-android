package app.overblast.feedback.internal

import app.overblast.feedback.Category
import app.overblast.feedback.LogLevel
import app.overblast.feedback.Reporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PayloadEncoderTest {
  private fun full(): FeedbackPayload = FeedbackPayload(
    message = "The total doesn't update when I remove an item",
    category = Category.IDEA,
    reporter = Reporter("ana@example.com", "Ana", "cus_8812"),
    context = FeedbackContext(
      screen = "CartActivity", title = "Your cart", appVersion = "2.4.1", build = "412",
      osVersion = "15", device = "Google Pixel 8", locale = "pt-PT", timeZone = "Europe/Lisbon",
      viewport = ContextViewport(412, 915, 2.625), sdkVersion = "0.1.0", deviceId = "8c1f-ab_9",
      extra = mapOf("plan" to "pro"),
    ),
    element = NativeElement(listOf("DecorView", "LinearLayout", "Button"), "Button", "remove_item", "Remove", FractionRect(0.61, 0.42, 0.2, 0.05), ElementViewport(412, 915)),
    screenshot = ScreenshotPart("image/jpeg", byteArrayOf(1, 2, 3), true),
    logs = listOf(LogEntry(1760440000123, LogLevel.ERROR, "TypeError", "logcat")),
    network = listOf(NetworkEntry(1760440000100, "post", "https://api.example.com/cart?id=1#x", 500, 312)),
  )

  @Suppress("UNCHECKED_CAST")
  private fun parse(p: FeedbackPayload): Map<String, Any?> = Json.parse(PayloadEncoder.encode(p)) as Map<String, Any?>

  @Test
  fun topLevelKeysAreExactlyTheContracts() {
    val m = parse(full())
    assertEquals(listOf("v", "message", "category", "reporter", "context", "element", "screenshot", "logs", "network"), m.keys.toList())
    assertEquals(1.0, m["v"])
    assertEquals("idea", m["category"])
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun nestedKeysAreExactlyTheContracts() {
    val m = parse(full())
    assertEquals(setOf("email", "name", "accountRef"), (m["reporter"] as Map<String, Any?>).keys)
    val ctx = m["context"] as Map<String, Any?>
    assertEquals(
      setOf("platform", "screen", "title", "appVersion", "build", "os", "osVersion", "device", "locale", "timeZone", "viewport", "sdk", "deviceId", "extra"),
      ctx.keys,
    )
    assertEquals("android", ctx["platform"])
    assertEquals("Android", ctx["os"])
    assertEquals(setOf("w", "h", "dpr"), (ctx["viewport"] as Map<String, Any?>).keys)
    assertEquals(mapOf("name" to "overblast-feedback-android", "version" to "0.1.0"), ctx["sdk"])
    val el = m["element"] as Map<String, Any?>
    assertEquals(listOf("kind", "path", "type", "id", "label", "rect", "viewport"), el.keys.toList())
    assertEquals("native", el["kind"])
    assertEquals(setOf("x", "y", "w", "h"), (el["rect"] as Map<String, Any?>).keys)
    assertEquals(setOf("w", "h"), (el["viewport"] as Map<String, Any?>).keys)
    val shot = m["screenshot"] as Map<String, Any?>
    assertEquals(listOf("contentType", "base64", "highlighted"), shot.keys.toList())
    assertEquals("AQID", shot["base64"])
    assertEquals(true, shot["highlighted"])
    val log = (m["logs"] as List<Map<String, Any?>>).single()
    assertEquals(listOf("t", "level", "msg", "src"), log.keys.toList())
    assertEquals(1760440000123.0, log["t"])
    assertEquals("error", log["level"])
    val net = (m["network"] as List<Map<String, Any?>>).single()
    assertEquals(listOf("t", "method", "url", "status", "ms"), net.keys.toList())
    assertEquals("https://api.example.com/cart", net["url"])
    assertEquals("POST", net["method"])
  }

  @Test
  fun optionalPartsAreLeftOutRatherThanNull() {
    val text = PayloadEncoder.encode(FeedbackPayload(message = "hi"))
    assertFalse(text.contains("null"))
    @Suppress("UNCHECKED_CAST")
    val m = Json.parse(text) as Map<String, Any?>
    assertEquals(listOf("v", "message", "category", "context"), m.keys.toList())
    assertEquals("bug", m["category"])
  }

  @Test
  fun emptyMessageIsRefusedByName() {
    try {
      PayloadEncoder.encode(FeedbackPayload(message = "   "))
      fail()
    } catch (e: PayloadRejected) {
      assertEquals("invalid_field", e.code)
      assertEquals("message", e.field)
    }
  }

  @Test
  fun invalidEmailIsRefusedByName() {
    try {
      PayloadEncoder.encode(FeedbackPayload(message = "x", reporter = Reporter(email = "not-an-email")))
      fail()
    } catch (e: PayloadRejected) {
      assertEquals("reporter.email", e.field)
    }
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun stringsAreCutToTheContractsLimits() {
    val long = "a".repeat(5000)
    val m = parse(
      FeedbackPayload(
        message = long,
        reporter = Reporter(name = long, accountRef = long),
        context = FeedbackContext(screen = long, title = long, appVersion = long, build = long, os = long, osVersion = long, device = long, locale = long, timeZone = long, deviceId = "x".repeat(100) + "!!"),
      ),
    )
    assertEquals(4000, (m["message"] as String).length)
    val r = m["reporter"] as Map<String, Any?>
    assertEquals(120, (r["name"] as String).length)
    assertEquals(200, (r["accountRef"] as String).length)
    val c = m["context"] as Map<String, Any?>
    assertEquals(200, (c["screen"] as String).length)
    assertEquals(200, (c["title"] as String).length)
    for (k in listOf("appVersion", "build", "os", "osVersion", "locale", "timeZone")) assertEquals(k, 40, (c[k] as String).length)
    assertEquals(80, (c["device"] as String).length)
    assertEquals("x".repeat(64), c["deviceId"])
  }

  @Test
  fun clipNeverSplitsASurrogatePair() {
    val s = "a".repeat(3) + "😀"
    assertEquals("aaa", Limits.clip(s, 4))
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun extraKeepsTwentyEntriesAndCutsValues() {
    val extra = (1..30).associate { "k$it" to "v".repeat(300) }
    val c = parse(FeedbackPayload(message = "x", context = FeedbackContext(extra = extra)))["context"] as Map<String, Any?>
    val e = c["extra"] as Map<String, Any?>
    assertEquals(20, e.size)
    assertTrue(e.values.all { (it as String).length == 200 })
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun elementPathKeepsTheDeepestTwelveStepsAndRectIsClamped() {
    val path = (1..20).map { "Step$it" + "x".repeat(100) }
    val el = PayloadEncoder.element(NativeElement(path, "T".repeat(100), "i".repeat(200), "l".repeat(200), FractionRect(-0.5, 0.9, 3.0, 0.5), ElementViewport(0, 10)))
    val p = el["path"] as List<String>
    assertEquals(12, p.size)
    assertTrue(p.first().startsWith("Step9"))
    assertTrue(p.all { it.length == 80 })
    assertEquals(80, (el["type"] as String).length)
    assertEquals(120, (el["id"] as String).length)
    assertEquals(120, (el["label"] as String).length)
    val r = el["rect"] as Map<String, Any?>
    assertEquals(0.0, r["x"] as Double, 0.0)
    assertEquals(0.9, r["y"] as Double, 1e-9)
    assertEquals(1.0, r["w"] as Double, 1e-9)
    assertEquals(0.1, r["h"] as Double, 1e-9)
    assertEquals(mapOf("w" to 1, "h" to 10), el["viewport"])
  }

  @Test
  fun screenshotOverFiveMegabytesIsRefused() {
    try {
      PayloadEncoder.encode(FeedbackPayload(message = "x", screenshot = ScreenshotPart("image/jpeg", ByteArray(Limits.SCREENSHOT_BYTES + 1), false)))
      fail()
    } catch (e: PayloadRejected) {
      assertEquals("too_large", e.code)
    }
  }

  @Test
  fun logsKeepTheNewestFiveHundred() {
    val logs = (1..700).map { LogEntry(it.toLong(), LogLevel.INFO, "line $it", "app") }
    val kept = PayloadEncoder.logs(logs)
    assertEquals(500, kept.size)
    assertEquals(201L, kept.first()["t"])
    assertEquals(700L, kept.last()["t"])
  }

  @Test
  fun logsStayUnderTheByteCapAndMessagesAreCut() {
    val logs = (1..500).map { LogEntry(it.toLong(), LogLevel.WARN, "m".repeat(5000), "logcat") }
    val kept = PayloadEncoder.logs(logs)
    assertTrue(kept.all { (it["msg"] as String).length == 4000 })
    val bytes = Json.encode(kept).toByteArray(Charsets.UTF_8).size
    assertTrue("$bytes bytes", bytes <= Limits.LOG_BYTES)
    assertEquals(500L, kept.last()["t"])
  }

  @Test
  fun networkKeepsTheNewestHundred() {
    val net = (1..150).map { NetworkEntry(it.toLong(), "get", "https://x.test/a?token=1", 200, 5) }
    val kept = PayloadEncoder.network(net)
    assertEquals(100, kept.size)
    assertEquals("https://x.test/a", kept.first()["url"])
    assertEquals(51L, kept.first()["t"])
  }

  @Test
  fun base64MatchesTheJdk() {
    for (n in 0..10) {
      val bytes = ByteArray(n) { (it * 37 - 5).toByte() }
      assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), Base64Codec.encode(bytes))
    }
  }

  @Test
  fun reporterWithNothingIsLeftOut() {
    assertNull(parse(FeedbackPayload(message = "x", reporter = Reporter()))["reporter"])
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun relaunchReportsCarryTheTriggerTheCrashAndTheLogFile() {
    val gz = byteArrayOf(0x1f, 0x8b.toByte(), 8)
    val p = full().copy(
      context = full().context.copy(openedAt = 1760440000000, trigger = "relaunch"),
      crash = CrashInfo("exception", 1760439990000, "java.lang.IllegalStateException: token=abc", "at a.b(C.kt:1)\nBearer abcdefghijkl", "handler"),
      logFile = LogFilePart(gz, 18240, 1760439400000, 1760440000000),
      logs = listOf(LogEntry(1, LogLevel.WARN, "retry", "app", repeat = 37, last = 9)),
    )
    val m = parse(p)
    assertEquals(listOf("v", "message", "category", "reporter", "context", "crash", "element", "screenshot", "logs", "logFile", "network"), m.keys.toList())
    val ctx = m["context"] as Map<String, Any?>
    assertEquals(1760440000000.0, ctx["openedAt"])
    assertEquals("relaunch", ctx["trigger"])
    val crash = m["crash"] as Map<String, Any?>
    assertEquals(listOf("kind", "at", "message", "stack", "source"), crash.keys.toList())
    assertEquals("java.lang.IllegalStateException: token=[redacted]", crash["message"])
    assertEquals("at a.b(C.kt:1)\nBearer [redacted]", crash["stack"])
    assertEquals("handler", crash["source"])
    val file = m["logFile"] as Map<String, Any?>
    assertEquals(listOf("contentType", "encoding", "base64", "lines", "from", "to"), file.keys.toList())
    assertEquals("application/x-ndjson", file["contentType"])
    assertEquals("gzip", file["encoding"])
    assertEquals(Base64Codec.encode(gz), file["base64"])
    assertEquals(18240.0, file["lines"])
    val log = (m["logs"] as List<Map<String, Any?>>).single()
    assertEquals(listOf("t", "level", "msg", "src", "repeat", "last"), log.keys.toList())
    assertEquals(37.0, log["repeat"])
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun aCrashKnownOnlyByItsMarkerHasJustKindAndSource() {
    val m = parse(full().copy(crash = CrashInfo("unknown", null, null, null, "marker")))
    assertEquals(mapOf("kind" to "unknown", "source" to "marker"), m["crash"])
  }
}
