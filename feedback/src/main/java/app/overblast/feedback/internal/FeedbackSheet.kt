package app.overblast.feedback.internal

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.overblast.feedback.Category
import app.overblast.feedback.FeedbackResult
import app.overblast.feedback.FeedbackStrings

// THE SHEET. Plain Views and two framework themes, so the library brings no Material or AppCompat into
// the app. It follows the activity's own night mode, which is what AppCompat's DayNight sets too.
//
// What the reporter controls, and the defaults the contract asks for (§6): the screenshot is OFF until
// they turn it on, they see it before sending and can remove it, and a line says what will be sent.

internal class Palette(val dark: Boolean) {
  val sheet = if (dark) Color.rgb(0x1C, 0x1C, 0x1E) else Color.WHITE
  val field = if (dark) Color.rgb(0x2C, 0x2C, 0x2E) else Color.rgb(0xF2, 0xF2, 0xF5)
  val text = if (dark) Color.rgb(0xF2, 0xF2, 0xF7) else Color.rgb(0x1C, 0x1C, 0x1E)
  val secondary = if (dark) Color.rgb(0xA1, 0xA1, 0xA6) else Color.rgb(0x6B, 0x6B, 0x70)
  val accent = if (dark) Color.rgb(0x5B, 0x8D, 0xFF) else Color.rgb(0x25, 0x63, 0xEB)
  val onAccent = Color.WHITE
  val danger = if (dark) Color.rgb(0xFF, 0x6B, 0x6B) else Color.rgb(0xC6, 0x28, 0x28)
  val highlight = Color.rgb(0xFF, 0x5A, 0x1F)
}

/**
 * Dark when the activity's THEME is dark, which is not always the system's night mode: an app with a
 * light-only theme on a phone in dark mode is a light app. Android 10 and later themes say which they
 * are (`android:isLightTheme`, which Material, AppCompat and MDC themes all set); before that, and for a
 * theme that does not say, the activity's night mode decides.
 */
internal fun isDark(activity: Activity): Boolean {
  if (android.os.Build.VERSION.SDK_INT >= 29) {
    val tv = TypedValue()
    if (activity.theme.resolveAttribute(android.R.attr.isLightTheme, tv, true) && tv.type == TypedValue.TYPE_INT_BOOLEAN) {
      return tv.data == 0
    }
  }
  return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

internal class SelectedElement(val element: NativeElement, val boundsInWindow: RectF, val summary: String)

internal class SheetDraft(
  val message: String,
  val category: Category,
  val email: String?,
  val selected: SelectedElement?,
  val includeScreenshot: Boolean,
)

internal class FeedbackSheet(
  private val activity: Activity,
  private val screenshot: Bitmap?,
  private val strings: FeedbackStrings,
  private val askEmail: Boolean,
  private val logsIncluded: Boolean,
  /** A report after an unexpected end (§3.4): it carries that session's logs and what ended it. */
  private val afterCrash: Boolean = false,
  /** False when the key has screenshots off (§2.1): the frozen screen is still there to point at, never attached. */
  private val offerScreenshot: Boolean = true,
  /** The categories the key takes (§2), the first being [initialCategory]'s fallback; with one, no choice is shown. */
  private val categories: List<Category> = Category.values().toList(),
  private val initialCategory: Category = categories.first(),
  private val hitTest: (Float, Float) -> SelectedElement?,
  private val submit: (SheetDraft, (FeedbackResult) -> Unit) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val ctx: Context = activity
  private val palette = Palette(isDark(activity))
  private val density = activity.resources.displayMetrics.density

  private var category = initialCategory
  private var selected: SelectedElement? = null
  private var highlightedCache: Bitmap? = null
  private var sending = false

  private lateinit var dialog: Dialog
  private lateinit var message: EditText
  private lateinit var chips: List<Pair<Category, TextView>>
  private lateinit var selectedRow: LinearLayout
  private lateinit var selectedText: TextView
  private lateinit var screenshotSwitch: Switch
  private lateinit var thumbRow: LinearLayout
  private lateinit var thumb: ImageView
  private var email: EditText? = null
  private lateinit var summary: TextView
  private lateinit var errorText: TextView
  private lateinit var send: TextView

  private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

  fun show() {
    val theme = if (palette.dark) android.R.style.Theme_Material_Dialog_NoActionBar else android.R.style.Theme_Material_Light_Dialog_NoActionBar
    dialog = Dialog(activity, theme)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(build())
    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnDismissListener { onClosed() }
    dialog.window?.let { w ->
      w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
      w.setGravity(Gravity.BOTTOM)
      val screenDp = activity.resources.displayMetrics.widthPixels / density
      w.setLayout(if (screenDp > 640) dp(600f) else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      @Suppress("DEPRECATION")
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    dialog.show()
    message.requestFocus()
  }

  // ── Layout ──────────────────────────────────────────────────────────────────────────────────

  private fun build(): View {
    val sheet = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = GradientDrawable().apply {
        setColor(palette.sheet)
        val r = dp(16f).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
      }
      setPadding(dp(20f), dp(12f), dp(20f), dp(16f))
    }
    sheet.addView(View(ctx).apply {
      background = GradientDrawable().apply {
        setColor(palette.secondary)
        alpha = 90
        cornerRadius = dp(2f).toFloat()
      }
    }, LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12f) })

    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(ctx).apply { isFillViewport = false; addView(body) }
    sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    val bugOnly = categories == listOf(Category.BUG)
    body.addView(label(if (bugOnly) strings.titleProblem else strings.title, 20f, palette.text, bold = true))
    body.addView(label(strings.subtitle, 14f, palette.secondary).apply { setPadding(0, dp(4f), 0, dp(14f)) })

    // Category
    val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    val names = mapOf(
      Category.BUG to strings.categoryBug,
      Category.IDEA to strings.categoryIdea,
      Category.QUESTION to strings.categoryQuestion,
      Category.OTHER to strings.categoryOther,
    )
    chips = categories.map { it to names.getValue(it) }.map { (c, text) ->
      val chip = label(text, 14f, palette.text).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
        isClickable = true
        contentDescription = text
        setOnClickListener { category = c; paintChips() }
      }
      chipRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
      c to chip
    }
    // One category is no choice: nothing to show.
    if (categories.size > 1) body.addView(HorizontalScroll(ctx, chipRow))
    paintChips()

    // Message
    message = field(strings.messageHint, minLines = 4).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      gravity = Gravity.TOP or Gravity.START
      maxLines = 8
      filters = arrayOf(InputFilter.LengthFilter(Limits.MESSAGE))
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
          if (errorText.visibility == View.VISIBLE) errorText.visibility = View.GONE
        }
      })
    }
    body.addView(message, fieldParams(top = 14f))

    // Point at the problem
    if (screenshot != null) {
      body.addView(outlinedButton(strings.pointAtProblem) { openPicker() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
      selectedRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(0, dp(8f), 0, 0)
      }
      selectedText = label("", 14f, palette.text)
      selectedRow.addView(selectedText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      selectedRow.addView(textButton(strings.clear) { selected = null; highlightedCache = null; refresh() })
      body.addView(selectedRow)

      // Screenshot, off by default
      val switchRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12f), 0, 0)
      }
      switchRow.addView(label(strings.includeScreenshot, 15f, palette.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      screenshotSwitch = Switch(ctx).apply {
        isChecked = false
        contentDescription = strings.includeScreenshot
        setOnCheckedChangeListener { _, _ -> refresh() }
      }
      switchRow.addView(screenshotSwitch)
      if (!offerScreenshot) switchRow.visibility = View.GONE
      body.addView(switchRow)

      thumbRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(0, dp(8f), 0, 0)
      }
      thumb = ImageView(ctx).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply { setColor(palette.field); cornerRadius = dp(8f).toFloat() }
        setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        contentDescription = strings.partScreenshot
        setOnClickListener { openPicker() }
      }
      thumbRow.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(160f)))
      thumbRow.addView(textButton(strings.removeScreenshot) { screenshotSwitch.isChecked = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12f) })
      body.addView(thumbRow)
    }

    // Email, only when the app did not say who this is
    if (askEmail) {
      email = field(strings.emailHint, minLines = 1).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        maxLines = 1
        filters = arrayOf(InputFilter.LengthFilter(Limits.EMAIL))
      }
      body.addView(email, fieldParams(top = 14f))
      body.addView(label(strings.emailHelp, 12f, palette.secondary).apply { setPadding(dp(4f), dp(4f), 0, 0) })
    }

    summary = label("", 12f, palette.secondary).apply { setPadding(0, dp(14f), 0, 0) }
    body.addView(summary)
    errorText = label("", 14f, palette.danger).apply {
      visibility = View.GONE
      setPadding(0, dp(10f), 0, 0)
    }
    body.addView(errorText)

    // Buttons, outside the scroll so Send is always reachable
    val buttons = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setPadding(0, dp(14f), 0, 0)
    }
    buttons.addView(textButton(strings.cancel) { if (!sending) dialog.dismiss() })
    send = filledButton(strings.send) { onSend() }
    buttons.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) })
    sheet.addView(buttons)

    sheet.setOnApplyWindowInsetsListener { v, insets ->
      v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(16f) + systemBars(insets).second)
      insets
    }
    refresh()
    return sheet
  }

  private fun paintChips() {
    for ((c, chip) in chips) {
      val on = c == category
      chip.setTextColor(if (on) palette.onAccent else palette.text)
      chip.background = GradientDrawable().apply {
        cornerRadius = dp(16f).toFloat()
        setColor(if (on) palette.accent else palette.field)
      }
      chip.isSelected = on
    }
  }

  private fun refresh() {
    if (screenshot != null) {
      val sel = selected
      selectedRow.visibility = if (sel != null) View.VISIBLE else View.GONE
      if (sel != null) selectedText.text = "${strings.selectedPrefix} ${sel.summary}"
      val on = screenshotSwitch.isChecked
      thumbRow.visibility = if (on) View.VISIBLE else View.GONE
      if (on) thumb.setImageBitmap(imageToSend())
    }
    val parts = ArrayList<String>()
    parts.add(strings.partMessage)
    if (selected != null) parts.add(strings.partElement)
    if (screenshot != null && screenshotSwitch.isChecked) parts.add(strings.partScreenshot)
    if (afterCrash) parts.add(strings.partCrash)
    if (logsIncluded) parts.add(if (afterCrash) strings.partLogsPrevious else strings.partLogs)
    parts.add(strings.partDevice)
    summary.text = "${strings.willBeSent} ${parts.joinToString(", ")}."
  }

  /** The screenshot as it will be sent: with the selected element outlined, when there is one. */
  private fun imageToSend(): Bitmap? {
    val shot = screenshot ?: return null
    val sel = selected ?: return shot
    highlightedCache?.let { return it }
    return ScreenCapture.highlighted(shot, sel.boundsInWindow, density).also { highlightedCache = it }
  }

  // ── Send ────────────────────────────────────────────────────────────────────────────────────

  private fun onSend() {
    if (sending) return
    val text = message.text.toString().trim()
    if (text.isEmpty()) {
      showError(strings.messageRequired)
      message.requestFocus()
      return
    }
    val typed = email?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (typed != null && !Limits.isEmail(typed)) {
      showError(strings.invalidEmail)
      email?.requestFocus()
      return
    }
    sending = true
    send.text = strings.sending
    send.isEnabled = false
    send.alpha = 0.6f
    errorText.visibility = View.GONE
    val draft = SheetDraft(
      message = text,
      category = category,
      email = typed,
      selected = selected,
      includeScreenshot = screenshot != null && screenshotSwitch.isChecked,
    )
    submit(draft) { result -> onResult(result) }
  }

  private fun onResult(result: FeedbackResult) {
    sending = false
    send.text = strings.send
    send.isEnabled = true
    send.alpha = 1f
    when (result) {
      is FeedbackResult.Sent -> {
        toast(result.notice ?: strings.sent)
        if (dialog.isShowing) dialog.dismiss()
      }
      is FeedbackResult.SavedForLater -> {
        toast("${result.message} ${strings.savedForLater}")
        if (dialog.isShowing) dialog.dismiss()
      }
      is FeedbackResult.Failed -> showError(result.message)
    }
  }

  private fun showError(text: String) {
    errorText.text = text
    errorText.visibility = View.VISIBLE
  }

  private fun toast(text: String) {
    Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
  }

  // ── Point at the problem ────────────────────────────────────────────────────────────────────

  private fun openPicker() {
    val shot = screenshot ?: return
    val picker = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    var pending: SelectedElement? = selected
    lateinit var use: TextView
    lateinit var caption: TextView
    val view = PickerView(ctx, shot, palette.highlight, density).apply {
      highlight = pending?.boundsInWindow
      onTap = { x, y ->
        val hit = hitTest(x, y)
        if (hit != null) {
          pending = hit
          highlight = hit.boundsInWindow
          caption.text = hit.summary
          use.isEnabled = true
          use.alpha = 1f
          invalidate()
        }
      }
    }
    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(64f); topMargin = dp(40f) })
    root.addView(label(strings.pointHint, 14f, Color.WHITE).apply {
      gravity = Gravity.CENTER
      setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f), Gravity.TOP))
    val bar = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16f), 0, dp(16f), 0)
      setBackgroundColor(Color.rgb(0x1C, 0x1C, 0x1E))
    }
    caption = label(pending?.summary ?: "", 13f, Color.rgb(0xD1, 0xD1, 0xD6)).apply { maxLines = 2 }
    bar.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    bar.addView(textButton(strings.cancel, Color.WHITE) { picker.dismiss() })
    use = filledButton(strings.pointDone) {
      selected = pending
      highlightedCache = null
      picker.dismiss()
      refresh()
    }.apply {
      isEnabled = pending != null
      alpha = if (pending != null) 1f else 0.5f
    }
    bar.addView(use, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) })
    root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f), Gravity.BOTTOM))
    // Android 15 draws every window edge to edge, so the status and navigation bars are inset by hand.
    root.setOnApplyWindowInsetsListener { v, insets ->
      val (top, bottom) = systemBars(insets)
      v.setPadding(0, top, 0, bottom)
      insets
    }
    picker.setContentView(root)
    picker.show()
  }

  @Suppress("DEPRECATION")
  private fun systemBars(insets: android.view.WindowInsets): Pair<Int, Int> =
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
      b.top to b.bottom
    } else {
      insets.systemWindowInsetTop to insets.systemWindowInsetBottom
    }

  // ── Small view factories ────────────────────────────────────────────────────────────────────

  private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
  }

  private fun field(hint: String, minLines: Int): EditText = EditText(ctx).apply {
    this.hint = hint
    this.minLines = minLines
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(palette.text)
    setHintTextColor(palette.secondary)
    background = GradientDrawable().apply {
      setColor(palette.field)
      cornerRadius = dp(10f).toFloat()
    }
    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
  }

  private fun fieldParams(top: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

  private fun textButton(text: String, color: Int = palette.accent, onClick: () -> Unit): TextView = label(text, 15f, color, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
    isClickable = true
    setOnClickListener { onClick() }
  }

  private fun filledButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, palette.onAccent, bold = true).apply {
    gravity = Gravity.CENTER
    minWidth = dp(88f)
    setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
    background = GradientDrawable().apply {
      setColor(palette.accent)
      cornerRadius = dp(20f).toFloat()
    }
    isClickable = true
    setOnClickListener { if (isEnabled) onClick() }
  }

  private fun outlinedButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, palette.accent, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
    background = GradientDrawable().apply {
      setColor(Color.TRANSPARENT)
      setStroke(dp(1f), palette.accent)
      cornerRadius = dp(10f).toFloat()
    }
    isClickable = true
    setOnClickListener { onClick() }
  }

  @Suppress("FunctionName")
  private fun HorizontalScroll(ctx: Context, child: View): View =
    android.widget.HorizontalScrollView(ctx).apply {
      isHorizontalScrollBarEnabled = false
      addView(child)
    }
}

/** The frozen screenshot, fit to the screen; a tap is reported in window pixels, the screenshot's own. */
internal class PickerView(
  context: Context,
  private val bitmap: Bitmap,
  highlightColor: Int,
  private val density: Float,
) : View(context) {
  var highlight: RectF? = null
  var onTap: ((Float, Float) -> Unit)? = null

  private val dst = RectF()
  private val box = RectF()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = highlightColor
    style = Paint.Style.STROKE
    strokeWidth = 3f * density
  }
  private val fill = Paint().apply {
    color = highlightColor
    alpha = 0x40
    style = Paint.Style.FILL
  }

  private fun layoutBitmap() {
    val pad = 8f * density
    val aw = width - 2 * pad
    val ah = height - 2 * pad
    if (aw <= 0 || ah <= 0) return
    val scale = minOf(aw / bitmap.width, ah / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val left = (width - w) / 2f
    val top = (height - h) / 2f
    dst.set(left, top, left + w, top + h)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    layoutBitmap()
    canvas.drawBitmap(bitmap, null, dst, paint)
    val h = highlight ?: return
    val sx = dst.width() / bitmap.width
    val sy = dst.height() / bitmap.height
    box.set(dst.left + h.left * sx, dst.top + h.top * sy, dst.left + h.right * sx, dst.top + h.bottom * sy)
    canvas.drawRect(box, fill)
    canvas.drawRect(box, stroke)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> return true
      MotionEvent.ACTION_UP -> {
        layoutBitmap()
        if (dst.contains(event.x, event.y) && dst.width() > 0) {
          val x = (event.x - dst.left) * bitmap.width / dst.width()
          val y = (event.y - dst.top) * bitmap.height / dst.height()
          onTap?.invoke(x, y)
        }
        performClick()
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean = super.performClick()
}
