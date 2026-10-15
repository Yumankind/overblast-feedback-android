package app.overblast.feedback.internal

import java.io.IOException

/** How one registration ended. A failure carries what to say in logcat and how long to leave the doors alone. */
internal sealed class RegistrationOutcome {
  data class Registered(val record: DeviceRecord, val reason: String?) : RegistrationOutcome()

  data class Failed(val code: String, val retryAfterSeconds: Long? = null) : RegistrationOutcome()
}

/**
 * ONE REGISTRATION (contract §8.2): a challenge, then hardware key attestation, then, when the device
 * cannot attest or the worker refuses the chain, a proof of work with a fresh challenge and a key that
 * is not attested. The key is generated under [alias] each time; a registration that fails deletes it,
 * so a half-made device never signs anything.
 *
 * Nothing here touches the store or a thread: [DeviceSession] runs this on its own thread and keeps
 * the answer.
 */
internal class DeviceRegistrar(
  endpoint: String,
  private val key: String,
  private val poster: HttpPoster,
  private val keys: DeviceKeys,
  private val alias: String,
  private val userAgent: String,
  private val powBudgetMs: Long = POW_BUDGET_MS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val base = endpoint.trimEnd('/')
  val challengeUrl: String = "$base/devices/challenge"
  val devicesUrl: String = "$base/devices"

  @Volatile var cancelled: Boolean = false

  fun register(): RegistrationOutcome {
    val first = when (val c = challenge()) {
      is Either.Left -> return c.value
      is Either.Right -> c.value
    }
    val chain = try {
      keys.generateAttested(alias, first.nonce)
    } catch (e: AttestationUnavailable) {
      null
    } catch (e: RuntimeException) {
      // A keystore that throws something the Android layer did not wrap is still "cannot attest".
      null
    }
    var powChallenge: DeviceChallenge? = first
    if (chain != null && chain.isNotEmpty()) {
      val body = linkedMapOf<String, Any?>(
        "challengeId" to first.id,
        "method" to METHOD_ANDROID_KEY,
        "chain" to chain.map { Base64Codec.encode(it) },
      )
      val answer = postJson(devicesUrl, body)
      when (DeviceRetryPolicy.afterAttestation(answer.status, answer.code)) {
        AttestationNext.DONE -> return registered(answer, METHOD_ANDROID_KEY)
        AttestationNext.GIVE_UP -> return fail(answer)
        AttestationNext.FALL_BACK_TO_POW -> powChallenge = null // the first challenge is spent
      }
    }
    val ch = powChallenge ?: when (val c = challenge()) {
      is Either.Left -> return c.value
      is Either.Right -> c.value
    }
    val spki = try {
      keys.generatePlain(alias)
    } catch (e: Exception) {
      keys.delete(alias)
      return RegistrationOutcome.Failed("key_unavailable")
    }
    val nonce = ProofOfWork.solve(ch.salt, ch.difficulty, clock() + powBudgetMs, clock) { cancelled }
    if (nonce == null) {
      keys.delete(alias)
      return RegistrationOutcome.Failed("pow_timeout")
    }
    val answer = postJson(
      devicesUrl,
      linkedMapOf("challengeId" to ch.id, "method" to METHOD_POW, "powNonce" to nonce, "publicKey" to Base64Url.encode(spki)),
    )
    return if (answer.status in 200..299) registered(answer, METHOD_POW) else fail(answer)
  }

  private fun registered(answer: Answer, method: String): RegistrationOutcome {
    val id = (answer.json?.get("deviceId") as? String)?.takeIf { it.isNotEmpty() }
    val level = DeviceRetryPolicy.parseLevel(answer.json?.get("level") as? String)
    if (id == null || level == null) {
      keys.delete(alias)
      return RegistrationOutcome.Failed("bad_answer")
    }
    return RegistrationOutcome.Registered(DeviceRecord(id, method, level, alias), answer.json?.get("reason") as? String)
  }

  private fun fail(answer: Answer): RegistrationOutcome {
    keys.delete(alias)
    return RegistrationOutcome.Failed(answer.code ?: if (answer.status == 0) "network" else "http_${answer.status}", answer.retryAfter)
  }

  private fun challenge(): Either<RegistrationOutcome.Failed, DeviceChallenge> {
    val answer = postJson(challengeUrl, emptyMap<String, Any?>())
    if (answer.status !in 200..299) {
      return Either.Left(RegistrationOutcome.Failed(answer.code ?: if (answer.status == 0) "network" else "http_${answer.status}", answer.retryAfter))
    }
    val c = DeviceChallenge.parse(answer.text) ?: return Either.Left(RegistrationOutcome.Failed("bad_challenge"))
    return Either.Right(c)
  }

  private class Answer(val status: Int, val text: String?, val json: Map<*, *>?) {
    val code: String? get() = (json?.get("code") as? String)?.takeIf { it.isNotEmpty() }
    val retryAfter: Long? get() = (json?.get("retryAfter") as? Number)?.toLong()?.coerceAtLeast(0)
  }

  private fun postJson(url: String, body: Map<String, Any?>): Answer = try {
    val headers = linkedMapOf(
      "Content-Type" to "application/json; charset=utf-8",
      "Accept" to "application/json",
      "X-Overblast-Feedback-Key" to key,
      "User-Agent" to userAgent,
    )
    val a = poster.post(url, headers, Json.encode(body).toByteArray(Charsets.UTF_8))
    Answer(a.status, a.body, a.body?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *>)
  } catch (e: IOException) {
    Answer(0, null, null)
  } catch (e: RuntimeException) {
    Answer(0, null, null)
  }

  private sealed class Either<out L, out R> {
    data class Left<L>(val value: L) : Either<L, Nothing>()
    data class Right<R>(val value: R) : Either<Nothing, R>()
  }

  companion object {
    const val METHOD_ANDROID_KEY = "android_key"
    const val METHOD_POW = "pow"

    /** The proof of work gives up after this long; the report then goes unsigned and the next one tries again. */
    const val POW_BUDGET_MS = 20_000L
  }
}
