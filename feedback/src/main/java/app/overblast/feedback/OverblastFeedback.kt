package app.overblast.feedback

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.overblast.feedback.internal.AnswerMapper
import app.overblast.feedback.internal.AnchorBuilder
import app.overblast.feedback.internal.DeviceInfo
import app.overblast.feedback.internal.DirectoryPendingFiles
import app.overblast.feedback.internal.FeedbackContext
import app.overblast.feedback.internal.FeedbackPayload
import app.overblast.feedback.internal.FeedbackSender
import app.overblast.feedback.internal.FeedbackSheet
import app.overblast.feedback.internal.Limits
import app.overblast.feedback.internal.LogRecorder
import app.overblast.feedback.internal.LogcatReader
import app.overblast.feedback.internal.NetworkRecorder
import app.overblast.feedback.internal.PayloadEncoder
import app.overblast.feedback.internal.PayloadRejected
import app.overblast.feedback.internal.PendingStore
import app.overblast.feedback.internal.ScreenCapture
import app.overblast.feedback.internal.ScreenshotPart
import app.overblast.feedback.internal.SelectedElement
import app.overblast.feedback.internal.ShakeDetector
import app.overblast.feedback.internal.SheetDraft
import app.overblast.feedback.internal.UrlConnectionPoster
import app.overblast.feedback.internal.ViewHitTester
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Feedback reports into an Overblast workspace: shake (or call [show]), say what is wrong, optionally
 * point at it and attach a screenshot, and it lands in the workspace as a task.
 *
 * ```kotlin
 * class App : Application() {
 *   override fun onCreate() {
 *     super.onCreate()
 *     OverblastFeedback.start(this, key = "obfk_...")
 *   }
 * }
 * ```
 */
public object OverblastFeedback {
  /** The production door. */
  public const val DEFAULT_ENDPOINT: String = "https://brain.deployd.network/v1/feedback"

  /** Sent as `context.sdk.name`. */
  public const val SDK_NAME: String = FeedbackContext.SDK_NAME

  /** This library's version, sent as `context.sdk.version`. */
  public const val VERSION: String = BuildConfig.SDK_VERSION

  private const val TAG = "OverblastFeedback"

  private class Config(
    val app: Application,
    val options: FeedbackOptions,
    val sender: FeedbackSender,
    val pending: PendingStore,
  )

  @Volatile private var config: Config? = null
  @Volatile private var reporter: Reporter? = null
  private val extras = LinkedHashMap<String, String>()
  private val logs = LogRecorder()
  private val network = NetworkRecorder()
  private val testers = CopyOnWriteArrayList<NativeHitTester>()
  private val main = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "overblast-feedback").apply { isDaemon = true }
  }

  // Main thread only.
  private var resumed: WeakReference<Activity>? = null
  private var shake: ShakeDetector? = null
  private var shakeOn = true
  private var sheetOpen = false
  private var callbacksRegistered = false

  /** True once [start] has been called. */
  @JvmStatic
  public val isStarted: Boolean get() = config != null

  /**
   * Starts the library. Call once, from `Application.onCreate`.
   *
   * @param key the workspace's feedback key, `obfk_...`, from the console's Feedback keys page. It is
   *   public by design (it only files reports, and the worker caps it), so it can live in the app.
   * @param endpoint the door; the default is production.
   * @param reporter who is reporting, when the app already knows; see [setReporter].
   * @throws IllegalArgumentException when [key] is not a feedback key or [endpoint] is not an http(s) URL.
   */
  @JvmStatic
  @JvmOverloads
  public fun start(
    application: Application,
    key: String,
    endpoint: String = DEFAULT_ENDPOINT,
    reporter: Reporter? = null,
    options: FeedbackOptions = FeedbackOptions(),
  ) {
    val k = key.trim()
    require(k.startsWith("obfk_") && k.length > 5) { "OverblastFeedback.start: the key must be a feedback key (obfk_...), from the console's Feedback keys page" }
    require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "OverblastFeedback.start: endpoint must be an http(s) URL, got '$endpoint'" }
    val pending = PendingStore(DirectoryPendingFiles(File(application.filesDir, "overblast-feedback/pending")))
    val sender = FeedbackSender(endpoint, k, UrlConnectionPoster(), pending, "$SDK_NAME/$VERSION")
    config = Config(application, options, sender, pending)
    if (reporter != null) setReporter(reporter.email, reporter.name, reporter.accountRef)
    shakeOn = options.shakeToReport
    discoverComposeTester()
    onMain {
      shake?.stop()
      shake = ShakeDetector(options.shakeThresholdG) { onShake() }
      if (!callbacksRegistered) {
        application.registerActivityLifecycleCallbacks(Lifecycle)
        callbacksRegistered = true
      }
      resumed?.get()?.let { if (shakeOn) shake?.start(it) }
    }
    // Reports kept after a 429 get one more attempt per start, never a loop.
    worker.execute { runCatching { sender.retryPending() } }
  }

  /**
   * Who is reporting, as the app knows them. Call again on sign-in and sign-out (with nulls).
   * An [email] here opens an email conversation on every report's task, and the sheet does not ask
   * for one. An invalid address is ignored with a warning in logcat rather than refused.
   */
  @JvmStatic
  @JvmOverloads
  public fun setReporter(email: String? = null, name: String? = null, accountRef: String? = null) {
    val e = email?.trim()?.takeIf { it.isNotEmpty() }
    val valid = if (e != null && !Limits.isEmail(e)) {
      Log.w(TAG, "setReporter: ignoring an email address that is not valid")
      null
    } else {
      e
    }
    val r = Reporter(valid, name?.trim()?.takeIf { it.isNotEmpty() }, accountRef?.trim()?.takeIf { it.isNotEmpty() })
    reporter = if (r.email == null && r.name == null && r.accountRef == null) null else r
  }

  /** Adds or (with null) removes one `context.extra` pair for every later report, beside [FeedbackOptions.extra]. */
  @JvmStatic
  public fun setExtra(key: String, value: String?) {
    synchronized(extras) { if (value == null) extras.remove(key) else extras[key] = value }
  }

  /** Turns shake-to-report on or off at runtime, for an app with its own setting for it. */
  @JvmStatic
  public fun setShakeToReport(enabled: Boolean) {
    onMain {
      shakeOn = enabled
      val a = resumed?.get()
      if (enabled && a != null) shake?.start(a) else shake?.stop()
    }
  }

  /** One line into the report's logs (the last 500 are kept), redacted as it arrives. */
  @JvmStatic
  public fun log(level: LogLevel, message: String) {
    logs.log(level, message)
  }

  /**
   * One network call into the report's `network` list (the last 100 are kept). The query string and
   * fragment are removed here; nothing else about the call is kept. [status] 0 means it never got an
   * answer. With OkHttp, the `:okhttp` module's interceptor calls this for you.
   */
  @JvmStatic
  public fun recordNetwork(method: String, url: String, status: Int, ms: Long) {
    network.record(method, url, status, ms)
  }

  /** Adds a tester for views that draw their own content. The Compose module's is added by itself. */
  @JvmStatic
  public fun addHitTester(tester: NativeHitTester) {
    if (tester !in testers) testers.add(tester)
  }

  /** Reports kept on the device after a 429, waiting for [retryPending]. */
  @JvmStatic
  public val pendingCount: Int get() = config?.pending?.count ?: 0

  /**
   * Sends the reports kept after a 429, oldest first, one attempt each. Stops at the first one that is
   * refused again. [done] gets how many went and how many are still kept, on the main thread.
   */
  @JvmStatic
  @JvmOverloads
  public fun retryPending(done: ((sent: Int, stillPending: Int) -> Unit)? = null) {
    val c = requireStarted("retryPending")
    worker.execute {
      val s = c.sender.retryPending()
      main.post { done?.invoke(s.sent, s.kept) }
    }
  }

  /**
   * Opens the report sheet over [activity]. The screen is captured first (blanked where it must be),
   * so the reporter can point at what is wrong and choose to attach it.
   */
  @JvmStatic
  public fun show(activity: Activity) {
    val c = requireStarted("show")
    onMain {
      if (sheetOpen || activity.isFinishing || activity.isDestroyed) return@onMain
      sheetOpen = true
      val context = contextFor(activity, c)
      ScreenCapture.capture(activity) { shot ->
        if (activity.isFinishing || activity.isDestroyed) {
          sheetOpen = false
          return@capture
        }
        try {
          openSheet(activity, c, context, shot)
        } catch (e: RuntimeException) {
          sheetOpen = false
          Log.e(TAG, "the report sheet could not open", e)
        }
      }
    }
  }

  /**
   * Files a report without the sheet: a "report a problem" menu item that already has the words, or a
   * crash-adjacent path. [includeScreenshot] captures the current activity. [done] is called on the
   * main thread.
   */
  @JvmStatic
  @JvmOverloads
  public fun report(
    message: String,
    category: Category = Category.BUG,
    includeScreenshot: Boolean = false,
    extra: Map<String, String> = emptyMap(),
    done: ((FeedbackResult) -> Unit)? = null,
  ) {
    val c = requireStarted("report")
    onMain {
      val activity = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
      val context = contextFor(activity, c).let { it.copy(extra = it.extra + extra) }
      val go = { shot: Bitmap? ->
        submit(c, message, category, null, null, context, shot, highlighted = false) { r ->
          done?.invoke(r)
          c.options.onResult?.invoke(r)
        }
      }
      if (includeScreenshot && activity != null) ScreenCapture.capture(activity) { go(it) } else go(null)
    }
  }

  // ── Inside ──────────────────────────────────────────────────────────────────────────────────

  private fun requireStarted(what: String): Config =
    config ?: throw IllegalStateException("OverblastFeedback.$what: call OverblastFeedback.start(application, key) first")

  private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
  }

  private fun onShake() {
    val a = resumed?.get() ?: return
    if (!shakeOn || sheetOpen || config == null) return
    show(a)
  }

  private fun contextFor(activity: Activity?, c: Config): FeedbackContext {
    val extra = LinkedHashMap(c.options.extra)
    synchronized(extras) { extra.putAll(extras) }
    return DeviceInfo.context(activity, c.app, c.options.screenName, extra, VERSION)
  }

  private fun openSheet(activity: Activity, c: Config, context: FeedbackContext, shot: Bitmap?) {
    val decor = activity.window.decorView
    val density = activity.resources.displayMetrics.density
    val sheet = FeedbackSheet(
      activity = activity,
      screenshot = shot,
      strings = c.options.strings,
      askEmail = reporter?.email == null,
      logsIncluded = c.options.sendLogs,
      hitTest = { x, y ->
        ViewHitTester.hitTest(decor, x, y, testers)?.let { hit ->
          val element = AnchorBuilder.build(
            path = hit.path, type = hit.type, id = hit.id, label = hit.label,
            leftPx = hit.boundsInWindow.left, topPx = hit.boundsInWindow.top,
            widthPx = hit.boundsInWindow.width(), heightPx = hit.boundsInWindow.height(),
            windowWidthPx = decor.width, windowHeightPx = decor.height, density = density,
          )
          SelectedElement(element, hit.boundsInWindow, describe(element.type, element.label, element.id))
        }
      },
      submit = { draft, back ->
        submitDraft(c, draft, context, shot, density) { r ->
          back(r)
          c.options.onResult?.invoke(r)
        }
      },
      onClosed = { sheetOpen = false },
    )
    sheet.show()
  }

  private fun describe(type: String, label: String?, id: String?): String = when {
    label != null -> "$type “${label.take(60)}”"
    id != null -> "$type #$id"
    else -> type
  }

  private fun submitDraft(c: Config, d: SheetDraft, context: FeedbackContext, shot: Bitmap?, density: Float, back: (FeedbackResult) -> Unit) {
    val base = reporter
    val who = if (d.email != null) Reporter(d.email, base?.name, base?.accountRef) else base
    val image = if (d.includeScreenshot) shot else null
    submit(c, d.message, d.category, who, d.selected, context, image, highlighted = d.selected != null, density = density, back = back)
  }

  private fun submit(
    c: Config,
    message: String,
    category: Category,
    who: Reporter?,
    selected: SelectedElement?,
    context: FeedbackContext,
    shot: Bitmap?,
    highlighted: Boolean,
    density: Float = 1f,
    back: (FeedbackResult) -> Unit,
  ) {
    val reporterNow = who ?: reporter
    worker.execute {
      val result = try {
        val image = shot?.let { s ->
          val drawn = if (highlighted && selected != null) ScreenCapture.highlighted(s, selected.boundsInWindow, density) else s
          val bytes = ScreenCapture.encodeJpeg(drawn)
          if (drawn !== s) drawn.recycle()
          bytes?.let { ScreenshotPart("image/jpeg", it, highlighted && selected != null) }
        }
        val opts = c.options
        val lines = if (opts.sendLogs) {
          val own = logs.snapshot()
          val cat = if (opts.captureLogcat) LogcatReader.read(android.os.Process.myPid()) else emptyList()
          own + cat
        } else {
          emptyList()
        }
        val payload = FeedbackPayload(
          message = message,
          category = category,
          reporter = reporterNow,
          context = context,
          element = selected?.element,
          screenshot = image,
          logs = lines,
          network = if (opts.sendNetwork) network.snapshot() else emptyList(),
        )
        c.sender.send(PayloadEncoder.encode(payload))
      } catch (e: PayloadRejected) {
        AnswerMapper.rejected(e)
      } catch (e: OutOfMemoryError) {
        FeedbackResult.Failed(0, "too_large", "The report is too large to prepare on this device. Try without the screenshot.")
      }
      main.post { back(result) }
    }
  }

  private fun discoverComposeTester() {
    // `:compose` is optional; when it is on the classpath its tester is installed without setup.
    try {
      val cls = Class.forName("app.overblast.feedback.compose.ComposeHitTester")
      (cls.getField("INSTANCE").get(null) as? NativeHitTester)?.let { addHitTester(it) }
    } catch (e: ClassNotFoundException) {
      // Not added: Compose screens are selected through the accessibility fallback.
    } catch (e: ReflectiveOperationException) {
      Log.w(TAG, "the Compose hit tester is present but could not be loaded", e)
    }
  }

  private object Lifecycle : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
      resumed = WeakReference(activity)
      if (shakeOn) shake?.start(activity)
    }

    override fun onActivityPaused(activity: Activity) {
      shake?.stop()
    }

    override fun onActivityDestroyed(activity: Activity) {
      if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  }
}
