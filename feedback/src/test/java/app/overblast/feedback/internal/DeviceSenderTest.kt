package app.overblast.feedback.internal

import app.overblast.feedback.DeviceLevel
import app.overblast.feedback.FeedbackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceSenderTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val endpoint = "https://x.test/v1/feedback"
  private val alias = "overblast-feedback-test"
  private val sent = HttpAnswer(201, """{"id":"fbr_1","taskId":"t","conversationId":null}""")
  private val direct = Executor { it.run() }

  private class Rig(val door: FakeDoor, val keys: FakeKeys, val store: MapDeviceStore, val session: DeviceSession, val sender: FeedbackSender, val pending: PendingStore)

  private fun rig(
    door: FakeDoor,
    keys: FakeKeys = FakeKeys(),
    store: MapDeviceStore = MapDeviceStore(),
    executor: Executor = direct,
    now: () -> Long = { 1_760_440_000_000 },
  ): Rig {
    val registrar = DeviceRegistrar(endpoint, "obfk_k", door, keys, alias, "ua")
    val session = DeviceSession(registrar, keys, store, alias, executor, now)
    val pending = PendingStore(DirectoryPendingFiles(tmp.newFolder()))
    val sender = FeedbackSender(endpoint, "obfk_k", door, pending, "ua", session, now)
    return Rig(door, keys, store, session, sender, pending)
  }

  private fun reports(door: FakeDoor) = door.requests.filter { it.url == endpoint }

  /** The signature over the canonical string of the exact bytes sent verifies with the registered key. */
  private fun assertSignedBy(r: Request, keys: FakeKeys, deviceId: String) {
    assertEquals(deviceId, r.headers["X-Overblast-Feedback-Device"])
    val time = r.headers.getValue("X-Overblast-Feedback-Time").toLong()
    val sig = Base64Url.decode(r.headers.getValue("X-Overblast-Feedback-Signature"))
    val canonical = CanonicalString.build(deviceId, time, r.body).toByteArray(Charsets.UTF_8)
    assertTrue("signature verifies", verifies(keys.pairs.getValue(alias).public, canonical, sig))
  }

  @Test
  fun theFirstReportRegistersAndIsSigned() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1")).on("/devices", registeredAnswer("fbd_1", "attested")).on("/feedback", sent)
    val r = rig(door)
    assertNull(r.session.level)
    assertTrue(r.sender.send("""{"v":1,"message":"hé"}""") is FeedbackResult.Sent)
    val report = reports(door).single()
    assertEquals("""{"v":1,"message":"hé"}""", report.text)
    assertSignedBy(report, r.keys, "fbd_1")
    assertEquals("1760440000000", report.headers["X-Overblast-Feedback-Time"])
    assertEquals(DeviceLevel.ATTESTED, r.session.level)
    assertEquals(DeviceRecord("fbd_1", "android_key", DeviceLevel.ATTESTED, alias), r.store.map[alias])
  }

  @Test
  fun aKeptDeviceIsUsedWithoutRegisteringAgain() {
    val door = FakeDoor().on("/feedback", sent, sent)
    val keys = FakeKeys().apply { generatePlain(alias) }
    val store = MapDeviceStore().apply { save(DeviceRecord("fbd_9", "pow", DeviceLevel.REGISTERED, alias)) }
    val r = rig(door, keys, store)
    assertEquals(DeviceLevel.REGISTERED, r.session.level)
    r.sender.send("{}")
    r.sender.send("{}")
    assertTrue(door.to("/devices/challenge").isEmpty())
    reports(door).forEach { assertSignedBy(it, keys, "fbd_9") }
  }

  @Test
  fun unknownDeviceForgetsRegistersAgainAndResendsOnce() {
    val door = FakeDoor()
      .on("/devices/challenge", challengeAnswer("c1"))
      .on("/devices", registeredAnswer("fbd_new", "attested"))
      .on("/feedback", HttpAnswer(401, """{"code":"unknown_device","error":"x"}"""), sent)
    val keys = FakeKeys().apply { generatePlain(alias) }
    val store = MapDeviceStore().apply { save(DeviceRecord("fbd_old", "pow", DeviceLevel.REGISTERED, alias)) }
    val r = rig(door, keys, store)
    assertTrue(r.sender.send("{}") is FeedbackResult.Sent)
    val two = reports(door)
    assertEquals(2, two.size)
    assertEquals("fbd_old", two[0].headers["X-Overblast-Feedback-Device"])
    assertSignedBy(two[1], keys, "fbd_new")
    assertEquals("fbd_new", store.map[alias]?.deviceId)
  }

  @Test
  fun aSecondDeviceRefusalIsNotRetried() {
    val door = FakeDoor()
      .on("/devices/challenge", challengeAnswer("c1"))
      .on("/devices", registeredAnswer("fbd_new", "attested"))
      .on("/feedback", HttpAnswer(401, """{"code":"device_signature_invalid","error":"Bad signature."}"""), HttpAnswer(401, """{"code":"device_signature_invalid","error":"Bad signature."}"""))
    val keys = FakeKeys().apply { generatePlain(alias) }
    val store = MapDeviceStore().apply { save(DeviceRecord("fbd_old", "pow", DeviceLevel.REGISTERED, alias)) }
    val r = rig(door, keys, store)
    val res = r.sender.send("{}") as FeedbackResult.Failed
    assertEquals("device_signature_invalid", res.code)
    assertEquals("Bad signature.", res.message)
    assertEquals(2, reports(door).size)
  }

  @Test
  fun rateLimitedDeviceDoorsSendUnsigned() {
    val door = FakeDoor().on("/devices/challenge", HttpAnswer(429, """{"code":"rate_limited","error":"x","retryAfter":30}""")).on("/feedback", sent, sent)
    var now = 1_000_000L
    val r = rig(door, now = { now })
    assertTrue(r.sender.send("{}") is FeedbackResult.Sent)
    val report = reports(door).single()
    assertFalse(report.headers.containsKey("X-Overblast-Feedback-Device"))
    assertFalse(report.headers.containsKey("X-Overblast-Feedback-Signature"))
    assertEquals(DeviceLevel.NONE, r.session.level)
    // Inside the retryAfter window the next report does not knock on the device doors again.
    now += 10_000
    r.sender.send("{}")
    assertEquals(1, door.to("/devices/challenge").size)
  }

  @Test
  fun deviceRequiredRegistersAndResendsOnce() {
    // A signer that has no device for the first report (a registration still running) and has one after.
    val keys = FakeKeys().apply { generatePlain(alias) }
    val record = DeviceRecord("fbd_r", "pow", DeviceLevel.REGISTERED, alias)
    val signer = object : DeviceSigner {
      var calls = 0
      override fun ready(waitMs: Long): DeviceRecord? = if (calls++ == 0) null else record
      override fun sign(record: DeviceRecord, data: ByteArray) = keys.sign(record.alias, data)
      override fun forget(record: DeviceRecord) = Unit
    }
    val door = FakeDoor().on("/feedback", HttpAnswer(403, """{"code":"device_required","error":"Verify first."}"""), sent)
    val sender = FeedbackSender(endpoint, "k", door, null, "ua", signer)
    assertTrue(sender.send("{}") is FeedbackResult.Sent)
    val two = reports(door)
    assertFalse(two[0].headers.containsKey("X-Overblast-Feedback-Device"))
    assertSignedBy(two[1], keys, "fbd_r")
  }

  @Test
  fun deviceRequiredWithoutAnyDeviceShowsTheSentence() {
    val door = FakeDoor()
      .on("/devices/challenge", HttpAnswer(503, "busy"))
      .on("/feedback", HttpAnswer(403, """{"code":"device_required","error":"This app only takes reports from verified devices."}"""))
    val r = rig(door)
    val res = r.sender.send("{}") as FeedbackResult.Failed
    assertEquals("This app only takes reports from verified devices.", res.message)
    // One registration attempt, then the quiet window: no second knock, and one report.
    assertEquals(1, door.to("/devices/challenge").size)
    assertEquals(1, reports(door).size)
  }

  @Test
  fun aMissingKeyIsForgottenAndTheReportGoesUnsigned() {
    // The record came back with an Auto Backup restore; the keystore key did not.
    val door = FakeDoor().on("/feedback", sent)
    val store = MapDeviceStore().apply { save(DeviceRecord("fbd_restored", "android_key", DeviceLevel.ATTESTED, alias)) }
    val r = rig(door, FakeKeys(), store)
    assertTrue(r.sender.send("{}") is FeedbackResult.Sent)
    assertFalse(reports(door).single().headers.containsKey("X-Overblast-Feedback-Device"))
    assertNull(store.map[alias])
  }

  @Test
  fun sendWaitsOnlyBrieflyForARegistrationUnderWay() {
    val gate = CountDownLatch(1)
    val door = object : HttpPoster {
      val inner = FakeDoor().on("/devices/challenge", challengeAnswer("c1")).on("/devices", registeredAnswer("fbd_slow", "attested")).on("/feedback", sent, sent)
      override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpAnswer {
        if (url.endsWith("/devices/challenge")) gate.await(10, TimeUnit.SECONDS)
        return inner.post(url, headers, body)
      }
    }
    val pool = Executors.newSingleThreadExecutor()
    try {
      val keys = FakeKeys()
      val store = MapDeviceStore()
      val session = DeviceSession(DeviceRegistrar(endpoint, "k", door, keys, alias, "ua"), keys, store, alias, pool)
      val sender = FeedbackSender(endpoint, "k", door, null, "ua", session)
      session.prepare()
      val started = System.nanoTime()
      assertTrue(sender.send("{}", waitMs = 200) is FeedbackResult.Sent)
      val tookMs = (System.nanoTime() - started) / 1_000_000
      assertTrue("waited $tookMs ms", tookMs in 150..2_000)
      assertFalse(door.inner.requests.last().headers.containsKey("X-Overblast-Feedback-Device"))
      // The registration carries on, and the next report is signed.
      gate.countDown()
      assertEquals("fbd_slow", session.ready(5_000)?.deviceId)
      sender.send("{}")
      assertEquals("fbd_slow", door.inner.requests.last().headers["X-Overblast-Feedback-Device"])
    } finally {
      gate.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun aKeptDraftIsStoredUnsignedAndSignedWhenItGoes() {
    var now = 1_000L
    val door = FakeDoor()
      .on("/devices/challenge", challengeAnswer("c1"))
      .on("/devices", registeredAnswer("fbd_1", "registered"))
      .on("/feedback", HttpAnswer(429, """{"code":"rate_limited","error":"Later.","retryAfter":60}"""), sent)
    val r = rig(door, now = { now })
    assertTrue(r.sender.send("""{"v":1}""") is FeedbackResult.SavedForLater)
    assertEquals("""{"v":1}""", r.pending.read(r.pending.names().single()))
    now = 400_000L
    assertEquals(RetrySummary(sent = 1, dropped = 0, kept = 0), r.sender.retryPending())
    val two = reports(door)
    assertEquals("1000", two[0].headers["X-Overblast-Feedback-Time"])
    assertEquals("400000", two[1].headers["X-Overblast-Feedback-Time"])
    assertSignedBy(two[1], r.keys, "fbd_1")
  }

  @Test
  fun withoutVerificationNothingIsSigned() {
    val door = FakeDoor().on("/feedback", sent)
    FeedbackSender(endpoint, "k", door, null).send("{}")
    assertTrue(door.requests.single().headers.keys.none { it.startsWith("X-Overblast-Feedback-") && it != "X-Overblast-Feedback-Key" })
  }
}
