package app.overblast.feedback

import android.app.Activity

/**
 * How the library behaves. Every field has a default; pass only what differs.
 *
 * @property shakeToReport open the report sheet when the phone is shaken. The accelerometer is only
 *   listened to while one of the app's activities is resumed.
 * @property shakeThresholdG how hard a shake must be, in g. 2.7 ignores walking and a phone set down
 *   on a table; lower it for a gentler shake.
 * @property captureLogcat follow the app's own logcat (`logcat --pid`) into the report's logs, through
 *   the same caps as [OverblastFeedback.log]. Only this process's lines can be read, with no permission.
 * @property sendLogs send logs at all: the lines from [OverblastFeedback.log] and, with
 *   [captureLogcat], logcat's. Off means the report carries no `logs`.
 * @property sendNetwork send the calls recorded with [OverblastFeedback.recordNetwork].
 * @property extra up to 20 string pairs added to every report's `context.extra` (plan, flags, ...).
 * @property screenName names the screen a report came from. The default is the activity's simple
 *   class name, which says little in a single-activity app; return your current route here instead.
 * @property strings every sentence the sheet shows, for apps that translate their interface.
 * @property onResult called on the main thread after each report from the sheet or [OverblastFeedback.report].
 * @property deviceVerification register this device with the worker on the first report (hardware key
 *   attestation, or a proof of work where that is unavailable) and sign every report with its key, so
 *   the worker's per-device limits count real phones. It only happens when the key's verification in
 *   the console is `flag` or `require` (it is `off` by default); off here turns it off whatever the
 *   key says, and a key set to `require` then refuses the reports. See the README's "Device verification".
 * @property offerReportAfterCrash when the last session ended unexpectedly (a crash, an ANR, the system
 *   killing the app in the foreground), ask once at the next launch whether to send a report with that
 *   session's logs and what ended it. Off discards it unasked.
 * @property errorPrompt allow the small "Something may have gone wrong. Tell us?" prompt after an
 *   error, when the key in the console has it on (it is off there by default). Off here turns it off
 *   for this app whatever the key says; on cannot turn on what the key has off.
 * @property appHosts the app's own API hosts (`api.example.com`, subdomains included): a request to one
 *   of them answered 5xx, or never answered, counts for the error prompt when the key lists
 *   `server_error` or `network`. Empty means no request counts.
 * @property theme the brand: accent colour, typeface and corner radius for everything the library
 *   draws. The default takes the accent from the activity's theme (`colorPrimary`).
 */
public class FeedbackOptions @JvmOverloads constructor(
  public val shakeToReport: Boolean = true,
  public val shakeThresholdG: Float = 2.7f,
  public val captureLogcat: Boolean = true,
  public val sendLogs: Boolean = true,
  public val sendNetwork: Boolean = true,
  public val extra: Map<String, String> = emptyMap(),
  public val screenName: ((Activity) -> String?)? = null,
  public val strings: FeedbackStrings = FeedbackStrings(),
  public val onResult: ((FeedbackResult) -> Unit)? = null,
  public val deviceVerification: Boolean = true,
  public val offerReportAfterCrash: Boolean = true,
  public val errorPrompt: Boolean = true,
  public val appHosts: List<String> = emptyList(),
  public val theme: FeedbackTheme = FeedbackTheme(),
)

/** The sheet's words. Replace any of them; a refusal from the server is shown in the server's own sentence. */
public data class FeedbackStrings(
  public val title: String = "Send feedback",
  /** The title when the key takes bug reports only (§2): no category choice, a problem report. */
  public val titleProblem: String = "Report a problem",
  public val subtitle: String = "Tell the team what went wrong, or what would make this better.",
  public val messageHint: String = "What happened? What did you expect?",
  public val categoryBug: String = "Bug",
  public val categoryIdea: String = "Idea",
  public val categoryQuestion: String = "Question",
  public val categoryOther: String = "Other",
  public val pointAtProblem: String = "Point at the problem",
  public val pointHint: String = "Tap the part that's wrong",
  public val pointDone: String = "Use this",
  public val selectedPrefix: String = "Selected:",
  public val clear: String = "Clear",
  public val includeScreenshot: String = "Include screenshot",
  public val removeScreenshot: String = "Remove",
  public val emailHint: String = "Email (optional)",
  public val emailHelp: String = "Only if you'd like the team to reply to you.",
  public val willBeSent: String = "Will be sent:",
  public val partMessage: String = "your message",
  public val partElement: String = "the selected element",
  public val partScreenshot: String = "the screenshot",
  public val partLogs: String = "logs from this session",
  public val partLogsPrevious: String = "logs from the last session",
  public val partCrash: String = "how the app closed",
  public val partDevice: String = "app and device details",
  public val cancel: String = "Cancel",
  public val send: String = "Send",
  public val sending: String = "Sending…",
  public val messageRequired: String = "Please say what happened.",
  public val invalidEmail: String = "That email address doesn't look right.",
  public val sent: String = "Thanks, your report was sent.",
  public val savedForLater: String = "Saved on this device. It will be sent later.",
  public val crashOffer: String = "The app closed unexpectedly. Send a report?",
  public val crashOfferSend: String = "Send report",
  public val crashOfferDecline: String = "Not now",
  public val errorPrompt: String = "Something may have gone wrong. Tell us?",
  public val errorPromptReport: String = "Report",
  public val errorPromptClose: String = "Close",
  public val pointNothing: String = "Nothing selected yet",
  public val pickChange: String = "Change",
  public val pickRemove: String = "Remove",
  public val editTitle: String = "Hide parts",
  public val editOpen: String = "Cover or blur anything you'd rather not send.",
  public val editHint: String = "Drag over anything you'd rather not send. Private areas are always hidden.",
  public val editCover: String = "Cover",
  public val editBlur: String = "Blur",
  public val editUndo: String = "Undo",
  public val editClear: String = "Clear",
  public val editDone: String = "Done",
  public val sentShort: String = "Sent, thank you",
  public val sentKnown: String = "Thanks. We already know about this one and we're on it.",
  public val sheetHandle: String = "Sheet handle",
  public val expand: String = "Expand",
  public val collapse: String = "Collapse",
)
