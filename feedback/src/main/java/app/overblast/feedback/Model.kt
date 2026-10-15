package app.overblast.feedback

// THE PUBLIC VALUE TYPES. Pure Kotlin on purpose (no `android.*` here), so the encoder and the answer
// mapping that use them are tested on a plain JVM.

/** What kind of report this is. The worker titles the task by it: `Bug:`, `Idea:`, `Question:`, `Feedback:`. */
public enum class Category(public val wire: String) {
  BUG("bug"),
  IDEA("idea"),
  QUESTION("question"),
  OTHER("other"),
}

/** The four levels the contract accepts. Logcat's V and D map to [DEBUG], F and A to [ERROR]. */
public enum class LogLevel(public val wire: String) {
  DEBUG("debug"),
  INFO("info"),
  WARN("warn"),
  ERROR("error"),
}

/**
 * How well the worker knows this device (contract §8.1), which sets how many reports it may file.
 *
 * - [ATTESTED]: the report key lives in secure hardware, and Google's certificate chain names this app
 *   and its signing certificate, which the feedback key in the console lists.
 * - [REGISTERED]: the device holds a registered key, proved by a proof of work, or by an attestation
 *   that did not meet everything above (an emulator, an unlocked bootloader, an app or signing
 *   certificate the key does not list).
 * - [NONE]: reports go unsigned: device verification is off, or registration failed (it is tried
 *   again with a later report).
 */
public enum class DeviceLevel(public val wire: String) {
  ATTESTED("attested"),
  REGISTERED("registered"),
  NONE("none"),
}

/**
 * The person reporting, as the integrating app knows them. Every field is optional.
 *
 * [email] is the one with a consequence: when it is present the worker opens an email conversation on
 * the task, so the team can answer. Without it there is nobody to answer and no conversation.
 * [accountRef] is the app's own id for this person (a customer id, a user id), at most 200 chars.
 */
public data class Reporter(
  public val email: String? = null,
  public val name: String? = null,
  public val accountRef: String? = null,
)

/** How a report ended. Every sentence in here can be shown to the reporter as it is. */
public sealed class FeedbackResult {
  /**
   * Filed. [notice] is set when the worker filed the task but said something about it, which today
   * means `507 storage_full`: the task exists, the screenshot and the report file do not. On that
   * answer the ids are whatever the worker included, possibly none.
   */
  public data class Sent(
    public val id: String?,
    public val taskId: String?,
    public val conversationId: String?,
    public val notice: String? = null,
  ) : FeedbackResult()

  /**
   * The worker answered `429 rate_limited`. The report was kept on the device and is sent by
   * [OverblastFeedback.retryPending] or on the next start, once, never in a loop.
   */
  public data class SavedForLater(
    public val message: String,
    public val retryAfterSeconds: Long?,
  ) : FeedbackResult()

  /**
   * Refused, or never answered. [status] is the HTTP status, 0 when the request did not get an
   * answer; [code] is the contract's code (`invalid_field`, `unknown_key`, ...) or `network`; [field]
   * names the offending field on `invalid_field`.
   */
  public data class Failed(
    public val status: Int,
    public val code: String,
    public val message: String,
    public val field: String? = null,
    public val retryAfterSeconds: Long? = null,
  ) : FeedbackResult()
}
