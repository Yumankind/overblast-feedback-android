package app.overblast.feedback.internal

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// DEVICE VERIFICATION, THE ANDROID HALF (contract §8.2): the key in the Android Keystore and the record
// in SharedPreferences. Hardware key attestation needs no Google service and no credential from the
// integrator: the chain is made by the device's own secure hardware and verified by the worker against
// Google's public roots. Play Integrity is deliberately not used, because it would need the integrator's
// Google Cloud project.

internal class AndroidDeviceKeys : DeviceKeys {
  private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

  override fun generateAttested(alias: String, challenge: ByteArray): List<ByteArray> {
    try {
      generate(alias, challenge)
      val chain = keyStore().getCertificateChain(alias)
      // A chain of one is the keystore's self-signed placeholder: this device made a key but no attestation.
      if (chain == null || chain.size < 2) {
        delete(alias)
        throw AttestationUnavailable("no attestation chain (${chain?.size ?: 0} certificates)")
      }
      return chain.map { it.encoded }
    } catch (e: AttestationUnavailable) {
      throw e
    } catch (e: Exception) {
      delete(alias)
      throw AttestationUnavailable("key attestation is not available: ${e.javaClass.simpleName}", e)
    }
  }

  override fun generatePlain(alias: String): ByteArray = generate(alias, null).public.encoded

  override fun sign(alias: String, data: ByteArray): ByteArray? {
    val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
    return Signature.getInstance("SHA256withECDSA").run {
      initSign(key)
      update(data)
      sign()
    }
  }

  override fun delete(alias: String) {
    runCatching { keyStore().deleteEntry(alias) }
  }

  /** StrongBox first where the phone has one (API 28+), then the TEE, which every attesting phone has. */
  private fun generate(alias: String, challenge: ByteArray?): java.security.KeyPair {
    delete(alias)
    if (Build.VERSION.SDK_INT >= 28) {
      try {
        return generate(alias, challenge, strongBox = true)
      } catch (e: StrongBoxUnavailableException) {
        delete(alias)
      } catch (e: ProviderException) {
        // Some keystores refuse a StrongBox key with a plain ProviderException (a StrongBox that is busy,
        // or one that cannot attest); the TEE is then the next best place, not a reason to give up.
        delete(alias)
      }
    }
    return generate(alias, challenge, strongBox = false)
  }

  private fun generate(alias: String, challenge: ByteArray?, strongBox: Boolean): java.security.KeyPair {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .apply {
        if (challenge != null) setAttestationChallenge(challenge)
        if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
      }
      .build()
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
    kpg.initialize(spec)
    return kpg.generateKeyPair()
  }

  private companion object {
    const val PROVIDER = "AndroidKeyStore"
  }
}

/** One record per alias in the library's own preferences file, beside the install's random device id. */
internal class PrefsDeviceStore(context: Context) : DeviceStore {
  private val app = context.applicationContext
  private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  override fun load(alias: String): DeviceRecord? {
    val id = prefs.getString("$alias.id", null) ?: return null
    val method = prefs.getString("$alias.method", null) ?: return null
    val level = DeviceRetryPolicy.parseLevel(prefs.getString("$alias.level", null)) ?: return null
    return DeviceRecord(id, method, level, alias)
  }

  override fun save(record: DeviceRecord) {
    prefs.edit()
      .putString("${record.alias}.id", record.deviceId)
      .putString("${record.alias}.method", record.method)
      .putString("${record.alias}.level", record.level.wire)
      .apply()
  }

  override fun clear(alias: String) {
    prefs.edit().remove("$alias.id").remove("$alias.method").remove("$alias.level").apply()
  }

  private companion object {
    const val PREFS = "app.overblast.feedback.devices"
  }
}
