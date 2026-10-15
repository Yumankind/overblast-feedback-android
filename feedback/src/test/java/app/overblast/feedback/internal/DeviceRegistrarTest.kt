package app.overblast.feedback.internal

import app.overblast.feedback.DeviceLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceRegistrarTest {
  private val endpoint = "https://x.test/v1/feedback"
  private val alias = "overblast-feedback-test"

  private fun registrar(door: FakeDoor, keys: FakeKeys, powBudgetMs: Long = 20_000) =
    DeviceRegistrar(endpoint, "obfk_k", door, keys, alias, "ua/1", powBudgetMs)

  @Test
  fun doorsHangOffTheEndpoint() {
    val r = DeviceRegistrar("https://x.test/v1/feedback/", "k", FakeDoor(), FakeKeys(), alias, "ua")
    assertEquals("https://x.test/v1/feedback/devices/challenge", r.challengeUrl)
    assertEquals("https://x.test/v1/feedback/devices", r.devicesUrl)
  }

  @Test
  fun attestsWithTheChallengesNonceAndSendsTheChainLeafFirst() {
    val nonce = ByteArray(32) { (it * 3).toByte() }
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1", nonce = nonce)).on("/devices", registeredAnswer("fbd_1", "attested"))
    val keys = FakeKeys()
    val out = registrar(door, keys).register() as RegistrationOutcome.Registered
    assertEquals(DeviceRecord("fbd_1", "android_key", DeviceLevel.ATTESTED, alias), out.record)
    assertArrayEquals(nonce, keys.challenges.single())
    val challengeReq = door.to("/devices/challenge").single()
    assertEquals("obfk_k", challengeReq.headers["X-Overblast-Feedback-Key"])
    assertEquals("{}", challengeReq.text)
    val reg = door.to("/devices").single().json
    assertEquals(listOf("challengeId", "method", "chain"), reg.keys.toList())
    assertEquals("c1", reg["challengeId"])
    assertEquals("android_key", reg["method"])
    assertEquals(listOf("MAE=", "MAI=", "MAM="), reg["chain"])
    assertTrue(keys.pairs.containsKey(alias))
  }

  @Test
  fun aRegisteredAnswerToAnAttestationIsKept() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1")).on("/devices", registeredAnswer("fbd_1", "registered", "verified_boot_unverified"))
    val out = registrar(door, FakeKeys()).register() as RegistrationOutcome.Registered
    assertEquals(DeviceLevel.REGISTERED, out.record.level)
    assertEquals("android_key", out.record.method)
    assertEquals("verified_boot_unverified", out.reason)
    assertEquals(1, door.to("/devices").size)
  }

  @Test
  fun withoutAttestationItSolvesTheProofOfWorkOnTheSameChallenge() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1", difficulty = 10)).on("/devices", registeredAnswer("fbd_2", "registered"))
    val keys = FakeKeys(attest = false)
    val out = registrar(door, keys).register() as RegistrationOutcome.Registered
    assertEquals(DeviceRecord("fbd_2", "pow", DeviceLevel.REGISTERED, alias), out.record)
    assertEquals(1, door.to("/devices/challenge").size)
    val reg = door.to("/devices").single().json
    assertEquals(listOf("challengeId", "method", "powNonce", "publicKey"), reg.keys.toList())
    assertEquals("c1", reg["challengeId"])
    assertTrue(ProofOfWork.check("salt-c1", reg["powNonce"] as String, 10))
    val spki = Base64Url.decode(reg["publicKey"] as String)
    assertArrayEquals(keys.pairs.getValue(alias).public.encoded, spki)
    assertFalse((reg["publicKey"] as String).contains('=') || (reg["publicKey"] as String).contains('+'))
  }

  @Test
  fun aRefusedChainFallsBackToProofOfWorkWithAFreshChallenge() {
    val door = FakeDoor()
      .on("/devices/challenge", challengeAnswer("c1"), challengeAnswer("c2"))
      .on("/devices", HttpAnswer(403, """{"code":"attestation_invalid","error":"x"}"""), registeredAnswer("fbd_3", "registered"))
    val keys = FakeKeys()
    val out = registrar(door, keys).register() as RegistrationOutcome.Registered
    assertEquals("pow", out.record.method)
    val regs = door.to("/devices")
    assertEquals("android_key", regs[0].json["method"])
    assertEquals("pow", regs[1].json["method"])
    assertEquals("c2", regs[1].json["challengeId"])
    // The key that signs is the plain one whose SPKI was registered, not the refused attested one.
    assertArrayEquals(keys.pairs.getValue(alias).public.encoded, Base64Url.decode(regs[1].json["publicKey"] as String))
  }

  @Test
  fun aRateLimitedChallengeFailsWithItsRetryAfterAndMakesNoKey() {
    val door = FakeDoor().on("/devices/challenge", HttpAnswer(429, """{"code":"rate_limited","error":"x","retryAfter":120}"""))
    val keys = FakeKeys()
    assertEquals(RegistrationOutcome.Failed("rate_limited", 120), registrar(door, keys).register())
    assertTrue(keys.pairs.isEmpty())
  }

  @Test
  fun aFailedRegistrationDeletesItsKey() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1")).on("/devices", IOException("offline"))
    val keys = FakeKeys()
    assertEquals(RegistrationOutcome.Failed("network"), registrar(door, keys).register())
    assertTrue(keys.pairs.isEmpty())
  }

  @Test
  fun aProofOfWorkThatRunsOutOfTimeGivesUp() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1", difficulty = 256))
    val keys = FakeKeys(attest = false)
    assertEquals(RegistrationOutcome.Failed("pow_timeout"), registrar(door, keys, powBudgetMs = 50).register())
    assertTrue(keys.pairs.isEmpty())
    assertTrue(door.to("/devices").isEmpty())
  }

  @Test
  fun anAnswerWithoutADeviceIdIsAFailure() {
    val door = FakeDoor().on("/devices/challenge", challengeAnswer("c1")).on("/devices", HttpAnswer(201, "{}"))
    val keys = FakeKeys()
    assertEquals(RegistrationOutcome.Failed("bad_answer"), registrar(door, keys).register())
    assertTrue(keys.pairs.isEmpty())
  }
}
