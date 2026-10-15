package app.overblast.feedback.internal

import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

// Test doubles for device verification: a keystore made of JDK EC keys, a store in a map, and a door
// that answers by URL from a script and keeps every request it was sent.

internal class FakeKeys(var attest: Boolean = true) : DeviceKeys {
  val pairs = HashMap<String, KeyPair>()
  val challenges = ArrayList<ByteArray>()
  var signs = 0

  private fun gen(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

  override fun generateAttested(alias: String, challenge: ByteArray): List<ByteArray> {
    if (!attest) throw AttestationUnavailable("not on this test device")
    pairs[alias] = gen()
    challenges.add(challenge)
    return listOf(byteArrayOf(0x30, 0x01), byteArrayOf(0x30, 0x02), byteArrayOf(0x30, 0x03))
  }

  override fun generatePlain(alias: String): ByteArray {
    val p = gen()
    pairs[alias] = p
    return p.public.encoded
  }

  override fun sign(alias: String, data: ByteArray): ByteArray? {
    val p = pairs[alias] ?: return null
    signs++
    return Signature.getInstance("SHA256withECDSA").run {
      initSign(p.private)
      update(data)
      sign()
    }
  }

  override fun delete(alias: String) {
    pairs.remove(alias)
  }
}

internal class MapDeviceStore : DeviceStore {
  val map = HashMap<String, DeviceRecord>()
  override fun load(alias: String) = map[alias]
  override fun save(record: DeviceRecord) {
    map[record.alias] = record
  }
  override fun clear(alias: String) {
    map.remove(alias)
  }
}

internal class Request(val url: String, val headers: Map<String, String>, val body: ByteArray) {
  val text: String get() = String(body, Charsets.UTF_8)
  val json: Map<*, *> get() = Json.parse(text) as Map<*, *>
}

/** Answers each URL's requests in order from its own queue; [IOException] values are thrown. */
internal class FakeDoor : HttpPoster {
  val requests = ArrayList<Request>()
  private val scripts = HashMap<String, ArrayDeque<Any>>()

  fun on(urlSuffix: String, vararg answers: Any): FakeDoor {
    scripts.getOrPut(urlSuffix) { ArrayDeque() }.addAll(answers)
    return this
  }

  fun to(urlSuffix: String): List<Request> = requests.filter { it.url.endsWith(urlSuffix) }

  @Synchronized
  override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpAnswer {
    requests.add(Request(url, headers, body))
    val key = scripts.keys.filter { url.endsWith(it) }.maxByOrNull { it.length } ?: error("no script for $url")
    return when (val a = scripts.getValue(key).removeFirstOrNull() ?: error("script for $key ran out")) {
      is HttpAnswer -> a
      is IOException -> throw a
      else -> error("bad answer $a")
    }
  }
}

internal fun challengeAnswer(id: String, difficulty: Int = 4, nonce: ByteArray = ByteArray(32) { 7 }): HttpAnswer =
  HttpAnswer(200, """{"challenge":{"id":"$id","salt":"salt-$id","difficulty":$difficulty,"expiresAt":0,"nonce":"${Base64Url.encode(nonce)}"}}""")

internal fun registeredAnswer(deviceId: String, level: String, reason: String? = null): HttpAnswer =
  HttpAnswer(201, """{"deviceId":"$deviceId","level":"$level","reason":${reason?.let { "\"$it\"" } ?: "null"}}""")

internal fun publicKeyOf(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

internal fun verifies(key: PublicKey, data: ByteArray, sig: ByteArray): Boolean =
  Signature.getInstance("SHA256withECDSA").run {
    initVerify(key)
    update(data)
    verify(sig)
  }
