package app.overblast.feedback

import android.app.Activity

/**
 * How the library behaves. Every field has a default; pass only what differs.
 *
 * @property shakeToReport open the report sheet when the phone is shaken. The accelerometer is only
 *   listened to while one of the app's activities is resumed.
 * @property shakeThresholdG how hard a shake must be, in g. 2.7 ignores walking and a phone set down
 *   on a table; lower it for a gentler shake.
 * @property captureLogcat add the last 500 lines of the app's own logcat (`logcat --pid`) to a report.
 *   Only this process's lines can be read, with no permission.
 * @property sendLogs send logs at all: the lines from [OverblastFeedback.log] and, with
 *   [captureLogcat], logcat's. Off means the report carries no `logs`.
 * @property sendNetwork send the calls recorded with [OverblastFeedback.recordNetwork].
 * @property extra up to 20 string pairs added to every report's `context.extra` (plan, flags, ...).
 * @property screenName names the screen a report came from. The default is the activity's simple
 *   class name, which says little in a single-activity app; return your current route here instead.
 * @property strings every sentence the sheet shows, for apps that translate their interface.
 * @property onResult called on the main thread after each report from the sheet or [OverblastFeedback.report].
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
)

/** The sheet's words. Replace any of them; a refusal from the server is shown in the server's own sentence. */
public data class FeedbackStrings(
  public val title: String = "Send feedback",
  public val subtitle: String = "Tell the team what went wrong, or what would make this better.",
  public val messageHint: String = "What happened? What did you expect?",
  public val categoryBug: String = "Bug",
  public val categoryIdea: String = "Idea",
  public val categoryQuestion: String = "Question",
  public val categoryOther: String = "Other",
  public val pointAtProblem: String = "Point at the problem",
  public val pointHint: String = "Tap the part of the screen that's wrong",
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
  public val partDevice: String = "app and device details",
  public val cancel: String = "Cancel",
  public val send: String = "Send",
  public val sending: String = "Sending…",
  public val messageRequired: String = "Please say what happened.",
  public val invalidEmail: String = "That email address doesn't look right.",
  public val sent: String = "Thanks, your report was sent.",
  public val savedForLater: String = "Saved on this device. It will be sent later.",
)
