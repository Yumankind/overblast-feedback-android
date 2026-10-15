package app.overblast.feedback.internal

import app.overblast.feedback.DeviceLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class DeviceCoreTest {
  @Test
  fun base64UrlMatchesTheJdkWithoutPadding() {
    val rnd = Random(7)
    for (n in 0..70) {
      val bytes = rnd.nextBytes(n)
      val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
      assertEquals(expected, Base64Url.encode(bytes))
      assertArrayEquals(bytes, Base64Url.decode(expected))
      // The standard alphabet with padding decodes to the same bytes.
      assertArrayEquals(bytes, Base64Url.decode(java.util.Base64.getEncoder().encodeToString(bytes)))
    }
  }

  @Test
  fun base64UrlRefusesWhatIsNotBase64() {
    assertTrue(runCatching { Base64Url.decode("ab*d") }.isFailure)
    assertTrue(runCatching { Base64Url.decode("abcde") }.isFailure)
  }

  @Test
  fun canonicalStringIsTheContractsFourLines() {
    val body = """{"v":1,"message":"Olá"}""".toByteArray(Charsets.UTF_8)
    val hash = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
    assertEquals("overblast-feedback-v1\nfbd_abc\n1760440000123\n$hash", CanonicalString.build("fbd_abc", 1760440000123, body))
  }

  @Test
  fun canonicalStringHashesBytesNotCharacters() {
    // The same text in two encodings is two different bodies.
    val a = CanonicalString.build("d", 1, "é".toByteArray(Charsets.UTF_8))
    val b = CanonicalString.build("d", 1, "é".toByteArray(Charsets.ISO_8859_1))
    assertNotEquals(a, b)
  }

  @Test
  fun leadingZeroBits() {
    assertEquals(0, ProofOfWork.leadingZeroBits(byteArrayOf(0x80.toByte())))
    assertEquals(7, ProofOfWork.leadingZeroBits(byteArrayOf(0x01)))
    assertEquals(8, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0xff.toByte())))
    assertEquals(12, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x0f)))
    assertEquals(16, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x00)))
  }

  @Test
  fun proofOfWorkSolvesAndChecks() {
    val nonce = ProofOfWork.solve("salt-1", 12, Long.MAX_VALUE)!!
    assertTrue(ProofOfWork.check("salt-1", nonce, 12))
    val digest = MessageDigest.getInstance("SHA-256").digest("salt-1:$nonce".toByteArray())
    assertTrue(ProofOfWork.leadingZeroBits(digest) >= 12)
    assertEquals("0", ProofOfWork.solve("x", 0, Long.MAX_VALUE))
  }

  @Test
  fun proofOfWorkGivesUpAtTheDeadlineAndWhenCancelled() {
    assertNull(ProofOfWork.solve("s", 256, deadlineMs = 0, clock = { 1 }))
    assertNull(ProofOfWork.solve("s", 256, deadlineMs = Long.MAX_VALUE, cancelled = { true }))
  }

  @Test
  fun reportRetryTable() {
    val t = DeviceRetryPolicy
    // A device the worker does not know, or a bad signature: once, and only for a signed report.
    assertEquals(ReportRetry.REREGISTER_AND_RESEND, t.afterReport(401, "unknown_device", signed = true, resent = false))
    assertEquals(ReportRetry.REREGISTER_AND_RESEND, t.afterReport(401, "device_signature_invalid", signed = true, resent = false))
    assertEquals(ReportRetry.NONE, t.afterReport(401, "unknown_device", signed = true, resent = true))
    assertEquals(ReportRetry.NONE, t.afterReport(401, "unknown_device", signed = false, resent = false))
    // The key is gone: nothing a device can fix.
    assertEquals(ReportRetry.NONE, t.afterReport(401, "unknown_key", signed = true, resent = false))
    // A key that requires a device: register and resend, once, and only when this report had none.
    assertEquals(ReportRetry.REGISTER_AND_RESEND, t.afterReport(403, "device_required", signed = false, resent = false))
    assertEquals(ReportRetry.NONE, t.afterReport(403, "device_required", signed = false, resent = true))
    assertEquals(ReportRetry.NONE, t.afterReport(403, "device_required", signed = true, resent = false))
    assertEquals(ReportRetry.NONE, t.afterReport(403, "key_disabled", signed = false, resent = false))
    // Everything else stands as answered, the 429 included (it goes to the pending store).
    for (s in listOf(201, 400, 413, 429, 500, 0)) assertEquals(ReportRetry.NONE, t.afterReport(s, null, signed = true, resent = false))
  }

  @Test
  fun attestationAnswerTable() {
    val t = DeviceRetryPolicy
    assertEquals(AttestationNext.DONE, t.afterAttestation(201, null))
    assertEquals(AttestationNext.FALL_BACK_TO_POW, t.afterAttestation(403, "attestation_invalid"))
    assertEquals(AttestationNext.FALL_BACK_TO_POW, t.afterAttestation(400, "invalid_field"))
    assertEquals(AttestationNext.GIVE_UP, t.afterAttestation(403, "challenge_invalid"))
    assertEquals(AttestationNext.GIVE_UP, t.afterAttestation(429, "rate_limited"))
    assertEquals(AttestationNext.GIVE_UP, t.afterAttestation(401, "unknown_key"))
    assertEquals(AttestationNext.GIVE_UP, t.afterAttestation(0, null))
  }

  @Test
  fun levels() {
    assertEquals(DeviceLevel.ATTESTED, DeviceRetryPolicy.parseLevel("attested"))
    assertEquals(DeviceLevel.REGISTERED, DeviceRetryPolicy.parseLevel("registered"))
    assertNull(DeviceRetryPolicy.parseLevel("none"))
    assertNull(DeviceRetryPolicy.parseLevel(null))
  }

  @Test
  fun challengeParses() {
    val nonce = ByteArray(32) { it.toByte() }
    val body = """{"challenge":{"id":"ch_1","salt":"s1","difficulty":14,"expiresAt":1760440300000,"nonce":"${Base64Url.encode(nonce)}"}}"""
    val c = DeviceChallenge.parse(body)!!
    assertEquals("ch_1", c.id)
    assertEquals("s1", c.salt)
    assertEquals(14, c.difficulty)
    assertArrayEquals(nonce, c.nonce)
    assertNull(DeviceChallenge.parse("""{"challenge":{"id":"x","salt":"s","difficulty":1}}"""))
    assertNull(DeviceChallenge.parse("""{"challenge":{"id":"x","salt":"s","difficulty":999,"nonce":"AAAA"}}"""))
    assertNull(DeviceChallenge.parse("<html>"))
    assertNull(DeviceChallenge.parse(null))
  }

  @Test
  fun aliasIsStablePerKeyAndDoor() {
    val a = deviceAlias("obfk_a", "https://x/v1/feedback")
    assertEquals(a, deviceAlias("obfk_a", "https://x/v1/feedback"))
    assertNotEquals(a, deviceAlias("obfk_b", "https://x/v1/feedback"))
    assertNotEquals(a, deviceAlias("obfk_a", "http://10.0.2.2:8787/v1/feedback"))
    assertTrue(a.matches(Regex("overblast-feedback-[0-9a-f]{16}")))
    assertFalse(a.contains("obfk"))
  }
}
