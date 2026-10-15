package app.overblast.feedback.internal

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.overblast.feedback.FeedbackStrings

// THE PILL (contract §3.6): not a dialog, a small view added to the activity's own content frame,
// above the navigation bar, in the app's light or dark, that goes by itself after about eight seconds.
// TalkBack announces it politely; with animations off (the animator scale at 0) it simply appears.

internal class ErrorPromptPill(
  private val activity: Activity,
  private val strings: FeedbackStrings,
  private val onReport: () -> Unit,
  private val onClose: () -> Unit,
  private val onGone: () -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private var view: View? = null
  private var finished = false
  private var detach: (() -> Unit)? = null

  fun show() {
    val host = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
    val palette = Palette(isDark(activity))
    val d = activity.resources.displayMetrics.density
    fun dp(v: Float) = (v * d + 0.5f).toInt()

    val pill = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16f), dp(6f), dp(6f), dp(6f))
      background = GradientDrawable().apply {
        setColor(palette.sheet)
        cornerRadius = dp(24f).toFloat()
        setStroke(dp(1f), palette.field)
      }
      elevation = dp(6f).toFloat()
      // Taps on the pill are its own, never the screen's below.
      isClickable = true
    }
    val text = TextView(activity).apply {
      this.text = strings.errorPrompt
      setTextColor(palette.text)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    pill.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val report = TextView(activity).apply {
      this.text = strings.errorPromptReport
      setTextColor(palette.accent)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTypeface(typeface, android.graphics.Typeface.BOLD)
      setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
      isClickable = true
      setOnClickListener { finish(); onReport() }
    }
    pill.addView(report)
    val close = TextView(activity).apply {
      this.text = "✕"
      contentDescription = strings.errorPromptClose
      setTextColor(palette.secondary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      setPadding(dp(8f), dp(10f), dp(10f), dp(10f))
      isClickable = true
      setOnClickListener { finish(); onClose() }
    }
    pill.addView(close)

    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
      val side = dp(16f)
      leftMargin = side
      rightMargin = side
      bottomMargin = dp(16f) + bottomInset(host)
    }
    pill.layoutParams = lp
    host.addView(pill)
    view = pill
    // Follow the keyboard and the system bars while it shows, so it never ends up behind the keyboard.
    // Root insets are the window's whole insets, whatever a parent consumed on the way down.
    val reposition = {
      val want = dp(16f) + bottomInset(host)
      val p = pill.layoutParams as? FrameLayout.LayoutParams
      if (p != null && p.bottomMargin != want) {
        p.bottomMargin = want
        pill.layoutParams = p
      }
    }
    pill.setOnApplyWindowInsetsListener { v, insets ->
      reposition()
      v.onApplyWindowInsets(insets)
    }
    val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { reposition() }
    host.viewTreeObserver.addOnGlobalLayoutListener(layout)
    detach = { runCatching { host.viewTreeObserver.removeOnGlobalLayoutListener(layout) } }
    if (animate(activity)) {
      pill.alpha = 0f
      pill.animate().alpha(1f).setDuration(200).start()
    }
    main.postDelayed({ if (!finished) { finish(); onGone() } }, ErrorPromptGate.SHOW_MS)
  }

  /** Takes the pill down without an answer (the screen went away). */
  fun dismissSilently() {
    if (!finished) {
      finish()
      onGone()
    }
  }

  private fun finish() {
    if (finished) return
    finished = true
    detach?.invoke()
    detach = null
    val v = view ?: return
    view = null
    val remove = { (v.parent as? ViewGroup)?.removeView(v) }
    if (animate(activity) && v.isAttachedToWindow) {
      v.animate().alpha(0f).setDuration(150).withEndAction { remove() }.start()
    } else {
      remove()
    }
  }

  private fun bottomInset(host: View): Int {
    val insets = host.rootWindowInsets ?: return 0
    return if (Build.VERSION.SDK_INT >= 30) {
      insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
    } else {
      @Suppress("DEPRECATION")
      insets.systemWindowInsetBottom
    }
  }

  companion object {
    /** Animations on, by the person's own setting: off when the animator scale is 0. */
    fun animate(context: Context): Boolean {
      if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false
      val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
      return scale > 0f
    }

    /**
     * The soft keyboard is up. API 30+ says so; before it, a bottom inset of more than a sixth of the
     * window is a keyboard (a navigation bar is far smaller).
     */
    fun imeVisible(activity: Activity): Boolean {
      val decor = activity.window?.decorView ?: return false
      val insets = decor.rootWindowInsets ?: return false
      if (Build.VERSION.SDK_INT >= 30) return insets.isVisible(WindowInsets.Type.ime())
      @Suppress("DEPRECATION")
      val bottom = insets.systemWindowInsetBottom
      return decor.height > 0 && bottom > decor.height / 6
    }
  }
}

internal class PrefsPromptStore(context: Context, private val scope: String) : PromptStore {
  private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  override fun load(): PromptMemory = PromptMemory(
    lastShownAt = prefs.getLong("$scope.shownAt", 0),
    closesInARow = prefs.getInt("$scope.closes", 0),
    quietUntil = prefs.getLong("$scope.quietUntil", 0),
  )

  override fun save(m: PromptMemory) {
    prefs.edit().putLong("$scope.shownAt", m.lastShownAt).putInt("$scope.closes", m.closesInARow).putLong("$scope.quietUntil", m.quietUntil).apply()
  }

  private companion object {
    const val PREFS = "app.overblast.feedback.prompt"
  }
}

internal class PrefsConfigStore(context: Context, private val scope: String) : ConfigStore {
  private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  override fun load(): StoredConfig? {
    val body = prefs.getString("$scope.body", null) ?: return null
    return StoredConfig(body, prefs.getString("$scope.etag", null), prefs.getLong("$scope.at", 0))
  }

  override fun save(c: StoredConfig) {
    prefs.edit().putString("$scope.body", c.body).putString("$scope.etag", c.etag).putLong("$scope.at", c.fetchedAt).apply()
  }

  private companion object {
    const val PREFS = "app.overblast.feedback.config"
  }
}

/**
 * When a text field's text last changed, for [Typing]: a TextWatcher on whichever EditText has focus in
 * the resumed activity, moved as focus moves. Compose text fields are covered by the keyboard check,
 * since a Compose field has no TextWatcher to attach.
 */
internal class TypingWatch {
  @Volatile var lastChangeAt: Long? = null
    private set

  private var activity: java.lang.ref.WeakReference<Activity>? = null
  private var field: java.lang.ref.WeakReference<TextView>? = null
  private val watcher = object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
      lastChangeAt = System.currentTimeMillis()
    }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
  }
  private val focus = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> watch(now) }

  fun attach(a: Activity) {
    detach()
    val decor = a.window?.decorView ?: return
    activity = java.lang.ref.WeakReference(a)
    runCatching { decor.viewTreeObserver.addOnGlobalFocusChangeListener(focus) }
    watch(decor.findFocus())
  }

  fun detach() {
    unwatch()
    activity?.get()?.window?.decorView?.let { d -> runCatching { d.viewTreeObserver.removeOnGlobalFocusChangeListener(focus) } }
    activity = null
  }

  private fun watch(v: View?) {
    unwatch()
    val tv = v as? TextView ?: return
    if (!tv.onCheckIsTextEditor()) return
    tv.addTextChangedListener(watcher)
    field = java.lang.ref.WeakReference(tv)
  }

  private fun unwatch() {
    field?.get()?.removeTextChangedListener(watcher)
    field = null
  }
}
