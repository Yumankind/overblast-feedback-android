package app.overblast.feedback.internal

import app.overblast.feedback.DeviceLevel
import java.security.MessageDigest

// DEVICE VERIFICATION, THE PURE HALF (contract §8). Everything here is plain Kotlin and the JDK, so the
// canonical string, the proof of work, base64url and the decisions about what to do after a refusal are
// pinned by JVM unit tests. The Android Keystore lives behind [DeviceKeys] and SharedPreferences behind
// [DeviceStore]; the sender and the registrar only ever see those two interfaces.

/** A device the worker registered: its id, how it proved itself, the level it got, and the keystore alias of its key. */
internal data class DeviceRecord(
  val deviceId: String,
  val method: String,
  val level: DeviceLevel,
  val alias: String,
)

/** Where a registered device is remembered between launches, one record per keystore alias. */
internal interface DeviceStore {
  fun load(alias: String): DeviceRecord?
  fun save(record: DeviceRecord)
  fun clear(alias: String)
}

/** Key generation could not produce an attestation chain on this device; the registrar falls back to `pow`. */
internal class AttestationUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The device's key pair, in the Android Keystore on a device and in memory in a test. Every method may
 * be slow (StrongBox key generation takes seconds) and is only called off the main thread.
 */
internal interface DeviceKeys {
  /**
   * Replaces whatever is under [alias] with a new P-256 signing key whose attestation carries
   * [challenge], and returns its certificate chain, leaf first, as DER.
   * @throws AttestationUnavailable when this device cannot attest a key.
   */
  fun generateAttested(alias: String, challenge: ByteArray): List<ByteArray>

  /** Replaces whatever is under [alias] with a new P-256 signing key, without attestation, and returns its SPKI as DER. */
  fun generatePlain(alias: String): ByteArray

  /** A DER ECDSA P-256 / SHA-256 signature of [data] with the key under [alias], or null when there is no such key. */
  fun sign(alias: String, data: ByteArray): ByteArray?

  fun delete(alias: String)
}

internal object Sha256 {
  fun of(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  fun hex(bytes: ByteArray): String = of(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** RFC 4648 §5 base64url without padding; [decode] also takes the standard alphabet and padding. */
internal object Base64Url {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

  fun encode(data: ByteArray): String =
    Base64Codec.encode(data).trimEnd('=').replace('+', '-').replace('/', '_')

  /** @throws IllegalArgumentException on a character outside both alphabets or an impossible length. */
  fun decode(text: String): ByteArray {
    val s = text.trim().trimEnd('=').replace('+', '-').replace('/', '_')
    require(s.length % 4 != 1) { "base64: impossible length ${s.length}" }
    val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in s) {
      val v = ALPHABET.indexOf(c)
      require(v >= 0) { "base64: bad character '$c'" }
      buffer = (buffer shl 6) or v
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out.write((buffer ushr bits) and 0xff)
      }
    }
    return out.toByteArray()
  }
}

/** The string a report's signature covers (§8.3). The body hash is over the exact bytes sent. */
internal object CanonicalString {
  const val PREFIX = "overblast-feedback-v1"

  fun build(deviceId: String, timeMs: Long, body: ByteArray): String =
    "$PREFIX\n$deviceId\n$timeMs\n${Base64Url.encode(Sha256.of(body))}"
}

/** The registration challenge (§8.2), as `POST .../devices/challenge` answers it. */
internal data class DeviceChallenge(
  val id: String,
  val salt: String,
  val difficulty: Int,
  val nonce: ByteArray,
) {
  override fun equals(other: Any?): Boolean =
    other is DeviceChallenge && other.id == id && other.salt == salt && other.difficulty == difficulty && other.nonce.contentEquals(nonce)

  override fun hashCode(): Int = id.hashCode()

  companion object {
    /** Null when the answer is not a challenge this library can use. */
    fun parse(body: String?): DeviceChallenge? {
      val root = body?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *> ?: return null
      val c = root["challenge"] as? Map<*, *> ?: return null
      val id = (c["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
      val salt = c["salt"] as? String ?: return null
      val difficulty = (c["difficulty"] as? Number)?.toInt()?.takeIf { it in 0..256 } ?: return null
      val nonce = (c["nonce"] as? String)?.let { runCatching { Base64Url.decode(it) }.getOrNull() } ?: return null
      if (nonce.isEmpty()) return null
      return DeviceChallenge(id, salt, difficulty, nonce)
    }
  }
}

/** `SHA-256("<salt>:<powNonce>")` with [difficulty] leading zero bits (§8.2). */
internal object ProofOfWork {
  fun leadingZeroBits(hash: ByteArray): Int {
    var n = 0
    for (b in hash) {
      val v = b.toInt() and 0xff
      if (v == 0) {
        n += 8
        continue
      }
      return n + Integer.numberOfLeadingZeros(v) - 24
    }
    return n
  }

  fun check(salt: String, nonce: String, difficulty: Int): Boolean =
    leadingZeroBits(Sha256.of("$salt:$nonce".toByteArray(Charsets.UTF_8))) >= difficulty

  /**
   * The first decimal nonce that meets [difficulty], or null once [deadlineMs] (on [clock]) has passed
   * or [cancelled] says so. The clock is read every 4,096 tries, so the abort is prompt and cheap.
   */
  fun solve(
    salt: String,
    difficulty: Int,
    deadlineMs: Long,
    clock: () -> Long = System::currentTimeMillis,
    cancelled: () -> Boolean = { false },
  ): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = "$salt:".toByteArray(Charsets.UTF_8)
    var counter = 0L
    while (true) {
      if (counter and 0xfff == 0L && (clock() >= deadlineMs || cancelled())) return null
      val candidate = counter.toString()
      digest.update(prefix)
      digest.update(candidate.toByteArray(Charsets.US_ASCII))
      if (leadingZeroBits(digest.digest()) >= difficulty) return candidate
      counter++
    }
  }
}

/** What a sender does after the door answers a report (§8.3). */
internal enum class ReportRetry {
  /** The answer stands. */
  NONE,

  /** The worker does not know the device or rejected its signature: forget it, register again, resend once. */
  REREGISTER_AND_RESEND,

  /** The key requires a device and this report had none: register, and resend once if that worked. */
  REGISTER_AND_RESEND,
}

/** What the registrar does after the worker answers an `android_key` registration (§8.2). */
internal enum class AttestationNext { DONE, FALL_BACK_TO_POW, GIVE_UP }

/**
 * THE DECISION TABLE, in one place so it is tested as a table rather than as branches in the sender.
 * [resent] is true on the one resend: nothing past it is ever retried.
 */
internal object DeviceRetryPolicy {
  const val UNKNOWN_DEVICE = "unknown_device"
  const val SIGNATURE_INVALID = "device_signature_invalid"
  const val DEVICE_REQUIRED = "device_required"
  const val ATTESTATION_INVALID = "attestation_invalid"

  fun afterReport(status: Int, code: String?, signed: Boolean, resent: Boolean): ReportRetry {
    if (resent) return ReportRetry.NONE
    if (status == 401 && signed && (code == UNKNOWN_DEVICE || code == SIGNATURE_INVALID)) return ReportRetry.REREGISTER_AND_RESEND
    if (status == 403 && !signed && code == DEVICE_REQUIRED) return ReportRetry.REGISTER_AND_RESEND
    return ReportRetry.NONE
  }

  /**
   * After the `android_key` registration answer. `attestation_invalid` is the contract's fall-back
   * signal. `invalid_field` falls back too: it means the worker could not read the chain (an older
   * worker, a chain longer than it allows), and a proof of work has no chain to misread.
   */
  fun afterAttestation(status: Int, code: String?): AttestationNext = when {
    status in 200..299 -> AttestationNext.DONE
    status == 403 && code == ATTESTATION_INVALID -> AttestationNext.FALL_BACK_TO_POW
    status == 400 && code == "invalid_field" -> AttestationNext.FALL_BACK_TO_POW
    else -> AttestationNext.GIVE_UP
  }

  fun parseLevel(wire: String?): DeviceLevel? = when (wire) {
    "attested" -> DeviceLevel.ATTESTED
    "registered" -> DeviceLevel.REGISTERED
    else -> null
  }
}

/** The keystore alias of this app's device key for one key and one door, so two configurations never share a key. */
internal fun deviceAlias(key: String, endpoint: String): String =
  "overblast-feedback-" + Sha256.hex("$key\n$endpoint".toByteArray(Charsets.UTF_8)).take(16)
