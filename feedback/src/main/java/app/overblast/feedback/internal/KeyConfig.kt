package app.overblast.feedback.internal

import app.overblast.feedback.Category
import app.overblast.feedback.FeedbackResult
import java.io.IOException

// WHAT THE KEY SAYS (contract §2.1): `GET <endpoint>/config`, read at start and at most every five
// minutes, kept on the device with its ETag, and used as kept when the door cannot be reached. Before
// any answer the library behaves as the defaults: no device registration, no error prompt, screenshots
// offered. The app's own options can only turn things off. Pure, so the rules are JVM-tested.

internal data class ErrorPromptConfig(
  val enabled: Boolean = false,
  val on: Set<String> = setOf(KIND_EXCEPTION),
  val minHoursBetween: Double = 24.0,
) {
  companion object {
    const val KIND_EXCEPTION = "exception"
    const val KIND_SERVER_ERROR = "server_error"
    const val KIND_NETWORK = "network"
  }
}

internal data class KeyConfig(
  val verification: String = VERIFICATION_OFF,
  val errorPrompt: ErrorPromptConfig = ErrorPromptConfig(),
  val screenshots: Boolean = true,
  /** The kinds of report the key takes, in the console's order; the first is the sheet's default (§2). */
  val categories: List<Category> = Category.values().toList(),
) {
  /** One category only: the sheet shows no choice. `[bug]` makes it "Report a problem". */
  val singleCategory: Boolean get() = categories.size == 1

  /**
   * The sheet's first category: the key's first, except that a report about something going wrong
   * (after a crash, from the error prompt) starts as a bug whenever the key takes bugs.
   */
  fun initialCategory(problem: Boolean): Category =
    if (problem && Category.BUG in categories) Category.BUG else categories.first()

  /** Null when [category] is one the key takes; otherwise the refusal `report()` returns without sending. */
  fun refusal(category: Category): FeedbackResult.Failed? {
    if (category in categories) return null
    val sentence = if (categories == listOf(Category.BUG)) "This app only takes problem reports." else "This app doesn't take this kind of report."
    return FeedbackResult.Failed(403, "category_not_accepted", sentence)
  }

  /** Whether the libraries register devices at all (§8): `flag` and `require` do, `off` does not. */
  val registersDevices: Boolean get() = verification == "flag" || verification == "require"

  companion object {
    const val VERIFICATION_OFF = "off"

    /** Null when [body] is not a config this library can read; unknown fields are ignored, missing ones default. */
    fun parse(body: String?): KeyConfig? {
      val m = body?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *> ?: return null
      val verification = (m["verification"] as? String)?.takeIf { it in setOf("off", "flag", "require") } ?: VERIFICATION_OFF
      val ep = m["errorPrompt"] as? Map<*, *>
      val prompt = if (ep == null) ErrorPromptConfig() else ErrorPromptConfig(
        enabled = ep["enabled"] == true,
        on = (ep["on"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: setOf(ErrorPromptConfig.KIND_EXCEPTION),
        minHoursBetween = (ep["minHoursBetween"] as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 24.0,
      )
      val categories = (m["categories"] as? List<*>)
        ?.mapNotNull { w -> Category.values().firstOrNull { it.wire == w } }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?: Category.values().toList()
      return KeyConfig(verification, prompt, m["screenshots"] as? Boolean ?: true, categories)
    }
  }
}

/** The kept answer: its text (so a later library reads it with its own rules), its ETag, and when it was read. */
internal data class StoredConfig(val body: String, val etag: String?, val fetchedAt: Long)

internal interface ConfigStore {
  fun load(): StoredConfig?
  fun save(c: StoredConfig)
}

internal class KeyConfigClient(
  endpoint: String,
  private val key: String,
  private val getter: HttpGetter,
  private val store: ConfigStore,
  private val userAgent: String,
  private val clock: () -> Long = System::currentTimeMillis,
  private val minIntervalMs: Long = 5 * 60_000L,
) {
  val url: String = endpoint.trimEnd('/') + "/config"

  @Volatile private var current: KeyConfig? = null
  @Volatile private var lastAttempt = Long.MIN_VALUE / 2
  private var etag: String? = null
  private var loaded = false

  /** The config in force: the last answer kept on the device, or the defaults. */
  val config: KeyConfig
    get() {
      load()
      return current ?: KeyConfig()
    }

  /** Whether [refresh] would ask the door now. */
  fun due(): Boolean = clock() - lastAttempt >= minIntervalMs

  /**
   * Asks the door when it is due (or [force]d), on the caller's thread. A 304 keeps the kept answer, a
   * failure keeps whatever was there. Returns the config in force afterwards.
   */
  @Synchronized
  fun refresh(force: Boolean = false): KeyConfig {
    load()
    if (!force && !due()) return config
    lastAttempt = clock()
    val headers = linkedMapOf(
      "Accept" to "application/json",
      "X-Overblast-Feedback-Key" to key,
      "User-Agent" to userAgent,
    )
    etag?.let { headers["If-None-Match"] = it }
    val answer = try {
      getter.get(url, headers)
    } catch (e: IOException) {
      return config
    } catch (e: RuntimeException) {
      return config
    }
    when {
      answer.status == 304 -> runCatching { store.load()?.let { store.save(it.copy(fetchedAt = clock())) } }
      answer.status in 200..299 -> {
        val parsed = KeyConfig.parse(answer.body) ?: return config
        current = parsed
        etag = answer.header("ETag")
        runCatching { store.save(StoredConfig(answer.body!!, etag, clock())) }
      }
    }
    return config
  }

  private fun load() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val kept = runCatching { store.load() }.getOrNull()
      if (kept != null) {
        current = KeyConfig.parse(kept.body)
        etag = kept.etag
      }
      loaded = true
    }
  }
}
