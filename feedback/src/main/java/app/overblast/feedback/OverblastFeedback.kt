package app.overblast.feedback

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.overblast.feedback.internal.AndroidDeviceKeys
import app.overblast.feedback.internal.AnswerMapper
import app.overblast.feedback.internal.BodyFitter
import app.overblast.feedback.internal.CrashWatch
import app.overblast.feedback.internal.AnchorBuilder
import app.overblast.feedback.internal.DeviceInfo
import app.overblast.feedback.internal.DeviceRegistrar
import app.overblast.feedback.internal.DeviceSession
import app.overblast.feedback.internal.ErrorPromptConfig
import app.overblast.feedback.internal.ErrorPromptGate
import app.overblast.feedback.internal.ErrorPromptPill
import app.overblast.feedback.internal.KeyConfigClient
import app.overblast.feedback.internal.PrefsConfigStore
import app.overblast.feedback.internal.PrefsPromptStore
import app.overblast.feedback.internal.PromptScene
import app.overblast.feedback.internal.PromptStep
import app.overblast.feedback.internal.Typing
import app.overblast.feedback.internal.TypingWatch
import app.overblast.feedback.internal.DirectoryPendingFiles
import app.overblast.feedback.internal.FeedbackContext
import app.overblast.feedback.internal.FeedbackPayload
import app.overblast.feedback.internal.FeedbackSender
import app.overblast.feedback.internal.FeedbackSheet
import app.overblast.feedback.internal.Limits
import app.overblast.feedback.internal.LogBook
import app.overblast.feedback.internal.LogExcerpt
import app.overblast.feedback.internal.LogFileBuilder
import app.overblast.feedback.internal.LogPersister
import app.overblast.feedback.internal.LogRecorder
import app.overblast.feedback.internal.LogcatReader
import app.overblast.feedback.internal.NetworkRecorder
import app.overblast.feedback.internal.PayloadEncoder
import app.overblast.feedback.internal.PayloadRejected
import app.overblast.feedback.internal.PendingStore
import app.overblast.feedback.internal.PrefsDeviceStore
import app.overblast.feedback.internal.PreviousSession
import app.overblast.feedback.internal.ScreenCapture
import app.overblast.feedback.internal.ScreenshotPart
import app.overblast.feedback.internal.SelectedElement
import app.overblast.feedback.internal.SessionFiles
import app.overblast.feedback.internal.ShakeDetector
import app.overblast.feedback.internal.SheetDraft
import app.overblast.feedback.internal.UrlConnectionPoster
import app.overblast.feedback.internal.ViewHitTester
import app.overblast.feedback.internal.deviceAlias
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

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
    val device: DeviceSession?,
    val keyConfig: KeyConfigClient,
    val prompt: ErrorPromptGate,
    val doorHost: String?,
  )

  @Volatile private var config: Config? = null
  @Volatile private var reporter: Reporter? = null
  private val extras = LinkedHashMap<String, String>()
  // One intake for the app's `log()` lines and its logcat (§3.5). It exists before `start`, so lines
  // logged in `Application.onCreate` ahead of it are kept too.
  private val book = LogBook(trackChanges = true)
  private val logs = LogRecorder(book)
  @Volatile private var collectLogs = true
  @Volatile private var crashWatch: CrashWatch? = null
  private var logcat: LogcatReader? = null
  private val network = NetworkRecorder()
  private val testers = CopyOnWriteArrayList<NativeHitTester>()
  private val main = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "overblast-feedback").apply { isDaemon = true }
  }

  // Registration has a thread of its own, so a report waiting on it (three seconds at most) never
  // waits behind itself on [worker].
  private val registration: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "overblast-feedback-device").apply { isDaemon = true }
  }

  // The disk side of the logs and the session marker, in order on one thread: the previous session is
  // moved aside before this one writes anything.
  private val diskThread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "overblast-feedback-log").apply { isDaemon = true }
  }

  // Main thread only.
  private var resumed: WeakReference<Activity>? = null
  private var shake: ShakeDetector? = null
  private var shakeOn = true
  private var sheetOpen = false
  private var callbacksRegistered = false
  private var startedActivities = 0
  private var offerShown = false
  private var offerOpen = false
  private val typingWatch = TypingWatch()
  // Weak: the pill holds its activity, and while it is up its own view and listeners keep it alive.
  private var pillRef: WeakReference<ErrorPromptPill>? = null
  private var pill: ErrorPromptPill?
    get() = pillRef?.get()
    set(value) {
      pillRef = value?.let { WeakReference(it) }
    }

  /** What opened a report, sent as `context.trigger` (§3.1). */
  private enum class Trigger(val wire: String) {
    SHAKE("shake"), BUTTON("button"), API("api"), RELAUNCH("relaunch"), ERROR_PROMPT("error_prompt"),
  }

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
    val userAgent = "$SDK_NAME/$VERSION"
    val poster = UrlConnectionPoster()
    val scope = deviceAlias(k, endpoint)
    // The key's settings (§2.1): the kept answer at once, the door's in the background.
    val keyConfig = KeyConfigClient(endpoint, k, poster, PrefsConfigStore(application, scope), userAgent)
    val device = if (options.deviceVerification) {
      val alias = scope
      val keys = AndroidDeviceKeys()
      DeviceSession(
        registrar = DeviceRegistrar(endpoint, k, poster, keys, alias, userAgent),
        keys = keys,
        store = PrefsDeviceStore(application),
        alias = alias,
        executor = registration,
        log = { Log.i(TAG, it) },
      )
    } else {
      null
    }
    val sender = FeedbackSender(
      endpoint, k, poster, pending, userAgent, device,
      deviceGate = { keyConfig.config.registersDevices },
      onDeviceRequired = { registration.execute { runCatching { keyConfig.refresh(force = true) } } },
    )
    val prompt = ErrorPromptGate(PrefsPromptStore(application, scope), System::currentTimeMillis, System.currentTimeMillis())
    val doorHost = runCatching { java.net.URL(endpoint).host }.getOrNull()
    config = Config(application, options, sender, pending, device, keyConfig, prompt, doorHost)
    registration.execute { runCatching { keyConfig.refresh(force = true) } }
    startCrashAndLogs(application, options)
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

  /**
   * One line into the report's logs, through the same intake as logcat: repeats collapse, a storm is
   * capped, and kept lines are redacted (see the README's Logs). Nothing is collected while the sheet is open.
   */
  @JvmStatic
  public fun log(level: LogLevel, message: String) {
    log(level, message, unexpected = false)
  }

  /**
   * As [log]; with [unexpected] on an [LogLevel.ERROR] line, the error also counts for the error prompt
   * (`exception`, §3.6), the way [recordError] does.
   */
  @JvmStatic
  public fun log(level: LogLevel, message: String, unexpected: Boolean) {
    if (collectLogs) logs.log(level, message)
    if (unexpected && level == LogLevel.ERROR) signalError(ErrorPromptConfig.KIND_EXCEPTION)
  }

  /**
   * One network call into the report's `network` list (the last 100 are kept). The query string and
   * fragment are removed here; nothing else about the call is kept. [status] 0 means it never got an
   * answer. With OkHttp, the `:okhttp` module's interceptor calls this for you.
   */
  @JvmStatic
  public fun recordNetwork(method: String, url: String, status: Int, ms: Long) {
    network.record(method, url, status, ms)
    val c = config ?: return
    val kind = ErrorPromptGate.kindOfRequest(status) ?: return
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
    if (ErrorPromptGate.isAppHost(host, c.options.appHosts, c.doorHost)) signalError(kind)
  }

  /**
   * An error the app did not expect, which it caught (and may rethrow): logged as an error line and,
   * when the key has the error prompt on for exceptions, the moment the prompt may ask "Tell us?".
   * An uncaught exception needs nothing: the app closes, and the next launch offers the report.
   */
  @JvmStatic
  public fun recordError(error: Throwable) {
    log(LogLevel.ERROR, "${error.javaClass.name}${error.message?.let { ": $it" } ?: ""}", unexpected = true)
  }

  /** Adds a tester for views that draw their own content. The Compose module's is added by itself. */
  @JvmStatic
  public fun addHitTester(tester: NativeHitTester) {
    if (tester !in testers) testers.add(tester)
  }

  /**
   * How well the worker knows this device (contract §8): [DeviceLevel.ATTESTED], [DeviceLevel.REGISTERED],
   * or [DeviceLevel.NONE] when reports go unsigned (device verification off, or registration failed).
   * Null until it is known: before [start], and on a fresh install until the first report has
   * registered the device. Reading it never starts a registration.
   */
  @JvmStatic
  public val deviceLevel: DeviceLevel?
    get() {
      val c = config ?: return null
      val d = c.device ?: return DeviceLevel.NONE
      if (!c.keyConfig.config.registersDevices) return DeviceLevel.NONE
      return d.level
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
    requireStarted("show")
    open(activity, Trigger.BUTTON, null)
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
    // A category the key does not take is refused here, unsent, in the worker's own words (§2).
    c.keyConfig.config.refusal(category)?.let { refused ->
      onMain {
        done?.invoke(refused)
        c.options.onResult?.invoke(refused)
      }
      return
    }
    val openedAt = System.currentTimeMillis()
    if (c.keyConfig.config.registersDevices) c.device?.prepare()
    onMain {
      val activity = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
      val context = contextFor(activity, c).let { it.copy(extra = it.extra + extra, openedAt = openedAt, trigger = Trigger.API.wire) }
      val go = { shot: Bitmap? ->
        submit(c, message, category, null, null, context, shot, highlighted = false, previous = null) { r ->
          done?.invoke(r)
          c.options.onResult?.invoke(r)
        }
      }
      val screenshots = c.keyConfig.config.screenshots
      if (includeScreenshot && screenshots && activity != null) ScreenCapture.capture(activity) { go(it) } else go(null)
    }
  }

  // ── Inside ──────────────────────────────────────────────────────────────────────────────────

  private fun startCrashAndLogs(app: Application, options: FeedbackOptions) {
    collectLogs = options.sendLogs
    if (!options.sendLogs) book.clear()
    if (crashWatch != null) return // a second start keeps the first one's session, handler and logcat
    val files = SessionFiles(File(app.filesDir, "overblast-feedback/session"))
    val persister = if (options.sendLogs) LogPersister(book, files.log, diskThread) else null
    val watch = CrashWatch(app, files, persister)
    crashWatch = watch
    // Queued first, so the previous session is moved aside before this one's first write.
    diskThread.execute {
      runCatching { watch.rollOver(withLogs = options.sendLogs) }
      main.post { afterRollOver() }
    }
    persister?.attach()
    watch.installHandler()
    if (options.sendLogs && options.captureLogcat) {
      logcat = LogcatReader(android.os.Process.myPid(), sink = { e ->
        if (collectLogs) book.add(e.t, e.level, e.msg, "logcat")
      }).also { it.start() }
    }
  }

  private fun afterRollOver() {
    val c = config ?: return
    val w = crashWatch ?: return
    if (!w.hasPending) return
    if (!c.options.offerReportAfterCrash) {
      diskThread.execute { w.discard() }
      return
    }
    maybeOffer()
  }

  /** The offer after an unexpected end (§3.4), once, on the first activity that resumes. */
  private fun maybeOffer() {
    val c = config ?: return
    val w = crashWatch ?: return
    if (offerShown || sheetOpen || !w.hasPending || !c.options.offerReportAfterCrash) return
    val a = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
    val previous = w.take() ?: return
    offerShown = true
    // Asked once: from here on the saved session lives only in memory, for this answer.
    diskThread.execute { w.discard() }
    try {
      offerOpen = true
      pill?.dismissSilently()
      CrashWatch.offer(a, c.options.strings, send = { offerOpen = false; open(a, Trigger.RELAUNCH, previous) }, decline = { offerOpen = false })
    } catch (e: RuntimeException) {
      offerOpen = false
      Log.w(TAG, "the crash report offer could not be shown", e)
    }
  }

  // ── The error prompt (§3.6) ─────────────────────────────────────────────────────────────────

  private fun signalError(kind: String) {
    val c = config ?: return
    if (c.prompt.onError(kind, c.keyConfig.config.errorPrompt, c.options.errorPrompt)) onMain { promptTick() }
  }

  /** Asks the gate what to do now, and shows the pill or asks again later. Main thread. */
  private fun promptTick() {
    val c = config ?: return
    val a = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    val scene = PromptScene(
      foreground = a != null && startedActivities > 0,
      typing = a != null && Typing.isTyping(System.currentTimeMillis(), ErrorPromptPill.imeVisible(a), typingWatch.lastChangeAt),
      sheetOrOfferOpen = sheetOpen || offerOpen || pill != null,
      // Another window over the activity (a dialog, a system alert) takes its focus.
      dialogUp = a != null && !a.hasWindowFocus(),
    )
    when (val step = c.prompt.evaluate(scene, c.keyConfig.config.errorPrompt, c.options.errorPrompt)) {
      is PromptStep.Wait -> {
        main.postDelayed({ promptTick() }, step.ms)
      }
      PromptStep.Show -> showPill(c, a ?: return)
      PromptStep.Drop -> Unit
    }
  }

  private fun showPill(c: Config, activity: Activity) {
    val p = ErrorPromptPill(
      activity, c.options.strings,
      onReport = {
        pill = null
        c.prompt.onReported()
        promptLine("error prompt: report")
        open(activity, Trigger.ERROR_PROMPT, null)
      },
      onClose = {
        pill = null
        c.prompt.onClosed()
        promptLine("error prompt dismissed")
      },
      onGone = {
        pill = null
        promptLine("error prompt went by itself")
      },
    )
    try {
      p.show()
      pill = p
      c.prompt.onShown()
      promptLine("error prompt shown")
    } catch (e: RuntimeException) {
      Log.w(TAG, "the error prompt could not be shown", e)
    }
  }

  private fun promptLine(text: String) {
    if (collectLogs) book.add(System.currentTimeMillis(), LogLevel.INFO, text, LogBook.SRC)
  }

  private fun requireStarted(what: String): Config =
    config ?: throw IllegalStateException("OverblastFeedback.$what: call OverblastFeedback.start(application, key) first")

  private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
  }

  private fun onShake() {
    val a = resumed?.get() ?: return
    if (!shakeOn || sheetOpen || config == null) return
    open(a, Trigger.SHAKE, null)
  }

  /**
   * The sheet, from a shake, the app's `show()`, or the crash offer. Log collection stops from this
   * moment until the sheet closes (§3.4), so the sheet's own activity never fills its report.
   */
  private fun open(activity: Activity, trigger: Trigger, previous: PreviousSession?) {
    val c = config ?: return
    onMain {
      if (sheetOpen || activity.isFinishing || activity.isDestroyed) return@onMain
      sheetOpen = true
      val openedAt = System.currentTimeMillis()
      book.paused = true
      // The device registers while the reporter types, so it is usually done by the time they press Send.
      if (c.keyConfig.config.registersDevices) c.device?.prepare()
      pill?.dismissSilently()
      val context = contextFor(activity, c).copy(openedAt = openedAt, trigger = trigger.wire)
      val closed = {
        sheetOpen = false
        book.paused = false
      }
      ScreenCapture.capture(activity) { shot ->
        if (activity.isFinishing || activity.isDestroyed) {
          closed()
          return@capture
        }
        try {
          openSheet(activity, c, context, shot, previous, closed)
        } catch (e: RuntimeException) {
          closed()
          Log.e(TAG, "the report sheet could not open", e)
        }
      }
    }
  }

  private fun contextFor(activity: Activity?, c: Config): FeedbackContext {
    val extra = LinkedHashMap(c.options.extra)
    synchronized(extras) { extra.putAll(extras) }
    return DeviceInfo.context(activity, c.app, c.options.screenName, extra, VERSION)
  }

  private fun openSheet(activity: Activity, c: Config, context: FeedbackContext, shot: Bitmap?, previous: PreviousSession?, closed: () -> Unit) {
    val decor = activity.window.decorView
    val density = activity.resources.displayMetrics.density
    val sheet = FeedbackSheet(
      activity = activity,
      screenshot = shot,
      offerScreenshot = c.keyConfig.config.screenshots,
      categories = c.keyConfig.config.categories,
      initialCategory = c.keyConfig.config.initialCategory(problem = previous != null || context.trigger == Trigger.ERROR_PROMPT.wire),
      strings = c.options.strings,
      askEmail = reporter?.email == null,
      logsIncluded = c.options.sendLogs && (previous == null || previous.logs.isNotEmpty()),
      afterCrash = previous != null,
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
        submitDraft(c, draft, context, shot, density, previous) { r ->
          back(r)
          c.options.onResult?.invoke(r)
        }
      },
      onClosed = closed,
    )
    sheet.show()
  }

  private fun describe(type: String, label: String?, id: String?): String = when {
    label != null -> "$type “${label.take(60)}”"
    id != null -> "$type #$id"
    else -> type
  }

  private fun submitDraft(c: Config, d: SheetDraft, context: FeedbackContext, shot: Bitmap?, density: Float, previous: PreviousSession?, back: (FeedbackResult) -> Unit) {
    val base = reporter
    val who = if (d.email != null) Reporter(d.email, base?.name, base?.accountRef) else base
    val image = if (d.includeScreenshot) shot else null
    submit(c, d.message, d.category, who, d.selected, context, image, highlighted = d.selected != null, density = density, previous = previous, back = back)
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
    previous: PreviousSession?,
    back: (FeedbackResult) -> Unit,
  ) {
    val reporterNow = who ?: reporter
    worker.execute {
      var drawn: Bitmap? = null
      val result = try {
        val opts = c.options
        val openedAt = context.openedAt ?: System.currentTimeMillis()
        // After a crash, the logs are that session's, saved on disk; otherwise this session's, up to the
        // moment the report was opened.
        val all = when {
          !opts.sendLogs -> emptyList()
          previous != null -> previous.logs
          else -> book.snapshot().filter { it.t <= openedAt }
        }
        val anchor = if (previous != null) previous.crash.at ?: all.lastOrNull()?.let { it.last ?: it.t } ?: openedAt else openedAt
        val image = shot?.let { s ->
          val d = if (highlighted && selected != null) ScreenCapture.highlighted(s, selected.boundsInWindow, density) else s
          drawn = d
          ScreenCapture.encodeJpeg(d)?.let { ScreenshotPart("image/jpeg", it, highlighted && selected != null) }
        }
        val excerpt = LogExcerpt.select(all, anchor)
        // The file is the whole buffer; when the excerpt already holds every line, it adds nothing.
        val logFile = if (excerpt.size < all.size) LogFileBuilder.build(all) else null
        val base = FeedbackPayload(
          message = message,
          category = category,
          reporter = reporterNow,
          context = context,
          element = selected?.element,
          logs = excerpt,
          network = if (opts.sendNetwork && previous == null) network.snapshot().filter { it.t <= openedAt } else emptyList(),
          crash = previous?.crash,
        )
        // The body stays under 8 MB: the log file gives way first, then the screenshot (§3.5).
        val baseBytes = PayloadEncoder.encode(base).toByteArray(Charsets.UTF_8).size
        val fitted = BodyFitter.fit(baseBytes, all, logFile, image) { part, target ->
          drawn?.let { d -> ScreenCapture.encodeJpeg(d, target)?.let { ScreenshotPart(part.contentType, it, part.highlighted) } }
        }
        c.sender.send(PayloadEncoder.encode(base.copy(screenshot = fitted.screenshot, logFile = fitted.logFile)))
      } catch (e: PayloadRejected) {
        AnswerMapper.rejected(e)
      } catch (e: OutOfMemoryError) {
        FeedbackResult.Failed(0, "too_large", "The report is too large to prepare on this device. Try without the screenshot.")
      } finally {
        drawn?.let { d -> if (d !== shot) d.recycle() }
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
      if (config?.let { it.options.errorPrompt && it.keyConfig.config.errorPrompt.enabled } == true) typingWatch.attach(activity)
      maybeOffer()
      // The key's settings, re-read at most every five minutes while the app is used.
      config?.keyConfig?.let { kc -> if (kc.due()) registration.execute { runCatching { kc.refresh() } } }
    }

    override fun onActivityPaused(activity: Activity) {
      shake?.stop()
      typingWatch.detach()
      pill?.dismissSilently()
    }

    override fun onActivityDestroyed(activity: Activity) {
      if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    // The session marker (§3.4): open while any activity is started, closed when the last one stops,
    // except for a configuration change, where the same screen comes straight back.
    override fun onActivityStarted(activity: Activity) {
      startedActivities++
      if (startedActivities == 1) crashWatch?.let { w -> diskThread.execute { runCatching { w.onForeground() } } }
    }

    override fun onActivityStopped(activity: Activity) {
      startedActivities = (startedActivities - 1).coerceAtLeast(0)
      if (startedActivities == 0 && !activity.isChangingConfigurations) {
        crashWatch?.let { w -> diskThread.execute { runCatching { w.onBackground() } } }
      }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  }
}
