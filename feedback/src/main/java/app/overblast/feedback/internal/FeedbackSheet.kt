package app.overblast.feedback.internal

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.overblast.feedback.Category
import app.overblast.feedback.FeedbackResult
import app.overblast.feedback.FeedbackStrings

// THE SHEET. Plain Views, so the library brings no Material or AppCompat into the app; the brand comes
// from [Look] (the app's accent, typeface and corner radius), and light or dark from the activity.
//
//  - a bottom sheet that opens half way and drags to full height by its handle, and goes full height
//    when the keyboard comes up; Send sits at its bottom, so it is above the keyboard at all times;
//  - the categories the key takes at the top (none when it takes one), then a large message field;
//  - "Point at the problem": the pick comes back as a thumbnail of that part of the screen, with
//    Change and Remove;
//  - the screenshot, off until the reporter turns it on (§6): a preview, and an editor to cover or blur
//    parts of it; the private masks are in its pixels and cannot be taken off;
//  - after Send, a short "Sent, thank you" in the sheet itself, then it closes; a refusal keeps it open.

internal class SelectedElement(val element: NativeElement, val boundsInWindow: RectF, val summary: String)

internal class SheetDraft(
  val message: String,
  val category: Category,
  val email: String?,
  val selected: SelectedElement?,
  val includeScreenshot: Boolean,
  /** The covers and blurs drawn on the screenshot, in its pixels, in order. */
  val edits: List<ImageEdit> = emptyList(),
)

internal class FeedbackSheet(
  private val activity: Activity,
  private val screenshot: Bitmap?,
  private val strings: FeedbackStrings,
  private val look: Look,
  private val askEmail: Boolean,
  private val logsIncluded: Boolean,
  /** A report after an unexpected end (§3.4): it carries that session's logs and what ended it. */
  private val afterCrash: Boolean = false,
  /** False when the key has screenshots off (§2.1): the frozen screen is still there to point at, never attached. */
  private val offerScreenshot: Boolean = true,
  /** The categories the key takes (§2); with one, no choice is shown. */
  private val categories: List<Category> = Category.values().toList(),
  private val initialCategory: Category = categories.first(),
  private val hitTest: (Float, Float) -> SelectedElement?,
  private val submit: (SheetDraft, (FeedbackResult) -> Unit) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val w = Widgets(activity, look)
  private val p = look.palette
  private val density = activity.resources.displayMetrics.density
  private val main = Handler(Looper.getMainLooper())

  private var category = initialCategory
  private var selected: SelectedElement? = null
  private var edits: List<ImageEdit> = emptyList()
  private var includeShot = false
  private var sending = false
  private var closing = false

  private lateinit var dialog: Dialog
  private lateinit var sheetLayout: SheetLayout
  private lateinit var sheet: LinearLayout
  private lateinit var message: EditText
  private var chips: List<Pair<Category, TextView>> = emptyList()
  private var pickHost: FrameLayout? = null
  private var shotSwitch: Switch? = null
  private var shotPreview: ImageView? = null
  private var shotBlock: LinearLayout? = null
  private var email: EditText? = null
  private lateinit var summary: TextView
  private lateinit var errorText: TextView
  private lateinit var send: TextView
  private lateinit var content: View
  private lateinit var handle: View

  fun show() {
    dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
    sheetLayout = SheetLayout(activity, look) { close() }
    sheetLayout.setSheet(build())
    dialog.setContentView(sheetLayout)
    dialog.setCancelable(true)
    dialog.setOnCancelListener { close() }
    dialog.setOnDismissListener { onClosed() }
    dialog.window?.let { win ->
      win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      win.setBackgroundDrawableResource(android.R.color.transparent)
      win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
      // The keyboard does not come up by itself: the sheet opens half way, and goes full height when
      // the reporter taps the message.
      @Suppress("DEPRECATION")
      win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
      if (Build.VERSION.SDK_INT >= 30) {
        // Deprecated on 35, where edge to edge is the rule anyway; on 30 to 34 it is how to get there.
        @Suppress("DEPRECATION")
        win.setDecorFitsSystemWindows(false)
      } else {
        @Suppress("DEPRECATION")
        win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      }
      @Suppress("DEPRECATION")
      win.statusBarColor = 0
      @Suppress("DEPRECATION")
      win.navigationBarColor = 0
    }
    dialog.setTitle(title())
    dialog.show()
    if (look.animate) {
      sheetLayout.scrim.alpha = 0f
      sheetLayout.scrim.animate().alpha(1f).setDuration(200).start()
      sheet.translationY = activity.resources.displayMetrics.heightPixels * 0.5f
      sheet.animate().translationY(0f).setDuration(240).start()
    }
  }

  private fun title(): String = if (categories == listOf(Category.BUG)) strings.titleProblem else strings.title

  // ── Layout ──────────────────────────────────────────────────────────────────────────────────

  private fun build(): View {
    sheet = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = android.graphics.drawable.GradientDrawable().apply {
        setColor(p.sheet)
        val r = w.dp(look.corner).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
      }
      isClickable = true // taps on the sheet are the sheet's, never the scrim's
    }

    // The handle: a 48 dp target, dragged to resize, tapped (or double-tapped by TalkBack) to toggle.
    handle = FrameLayout(activity).apply {
      minimumHeight = w.dp(28f)
      isClickable = true
      isFocusable = true
      contentDescription = strings.sheetHandle
      setOnClickListener { sheetLayout.toggle() }
      addView(View(activity).apply { background = w.rounded(ColorMath.argb(0x66, ColorMath.r(p.secondary), ColorMath.g(p.secondary), ColorMath.b(p.secondary)), 0.2f) },
        FrameLayout.LayoutParams(w.dp(40f), w.dp(4f), Gravity.CENTER))
      accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
          super.onInitializeAccessibilityNodeInfo(host, info)
          info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, if (sheetLayout.expanded) strings.collapse else strings.expand))
        }
      }
    }
    sheet.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w.dp(28f)))
    sheetLayout.dragWith(handle)

    val header = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(w.dp(20f), 0, w.dp(20f), w.dp(8f))
    }
    header.addView(w.label(title(), 20f, p.text, bold = true).apply {
      if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    })
    // The key's categories, at the top; one category is no choice, so nothing is shown.
    if (categories.size > 1) {
      val names = mapOf(
        Category.BUG to strings.categoryBug,
        Category.IDEA to strings.categoryIdea,
        Category.QUESTION to strings.categoryQuestion,
        Category.OTHER to strings.categoryOther,
      )
      val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
      chips = categories.map { c ->
        val chip = w.chip(names.getValue(c)) { category = c; paintChips() }
        row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = w.dp(8f) })
        c to chip
      }
      header.addView(HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = w.dp(12f) })
      paintChips()
    }
    sheet.addView(header)
    // Dragging the header moves the sheet too, as most bottom sheets do.
    sheetLayout.dragWith(header)

    val body = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(w.dp(20f), w.dp(4f), w.dp(20f), w.dp(12f))
    }
    val scroll = ScrollView(activity).apply {
      isFillViewport = true
      addView(body)
    }
    content = scroll
    sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    message = w.field(strings.messageHint, minLines = 5).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      gravity = Gravity.TOP or Gravity.START
      maxLines = 12
      filters = arrayOf(InputFilter.LengthFilter(Limits.MESSAGE))
      setOnFocusChangeListener { _, has -> if (has) sheetLayout.settle(expand = true) }
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
          if (errorText.visibility == View.VISIBLE) errorText.visibility = View.GONE
        }
      })
    }
    body.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    if (screenshot != null) {
      pickHost = FrameLayout(activity)
      body.addView(pickHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = w.dp(12f) })
      if (offerScreenshot) body.addView(buildShotBlock(screenshot))
    }

    if (askEmail) {
      email = w.field(strings.emailHint, minLines = 1).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        maxLines = 1
        filters = arrayOf(InputFilter.LengthFilter(Limits.EMAIL))
        setOnFocusChangeListener { _, has -> if (has) sheetLayout.settle(expand = true) }
      }
      body.addView(email, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = w.dp(14f) })
      body.addView(w.label(strings.emailHelp, 12f, p.secondary).apply { setPadding(w.dp(4f), w.dp(4f), 0, 0) })
    }

    summary = w.label("", 12f, p.secondary).apply { setPadding(0, w.dp(14f), 0, 0) }
    body.addView(summary)
    errorText = w.label("", 14f, p.danger).apply {
      visibility = View.GONE
      setPadding(0, w.dp(10f), 0, 0)
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }
    body.addView(errorText)

    // The bar, at the sheet's bottom, which [SheetLayout] keeps above the keyboard.
    val bar = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setPadding(w.dp(16f), w.dp(8f), w.dp(16f), w.dp(12f))
      setBackgroundColor(p.sheet)
    }
    bar.addView(w.textButton(strings.cancel) { if (!sending) close() })
    send = w.filledButton(strings.send) { onSend() }
    bar.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = w.dp(8f) })
    sheet.addView(View(activity).apply { setBackgroundColor(p.field) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w.dp(1f).coerceAtLeast(1)))
    sheet.addView(bar)

    refresh()
    return sheet
  }

  private fun buildShotBlock(shot: Bitmap): View {
    val block = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, w.dp(12f), 0, 0)
    }
    val row = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = w.dp(48f)
    }
    row.addView(w.label(strings.includeScreenshot, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sw = Switch(activity).apply {
      isChecked = false
      contentDescription = strings.includeScreenshot
      setOnCheckedChangeListener { _, on -> includeShot = on; refresh() }
    }
    shotSwitch = sw
    row.addView(sw)
    block.addView(row)
    val detail = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      visibility = View.GONE
    }
    val preview = ImageView(activity).apply {
      adjustViewBounds = true
      scaleType = ImageView.ScaleType.FIT_CENTER
      background = w.rounded(p.field, 0.5f)
      setPadding(w.dp(2f), w.dp(2f), w.dp(2f), w.dp(2f))
      contentDescription = "${strings.partScreenshot}. ${strings.editOpen}"
      isClickable = true
      setOnClickListener { openEditorNow(shot) }
    }
    shotPreview = preview
    detail.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, w.dp(200f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = w.dp(4f) })
    val actions = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    actions.addView(w.label(strings.editOpen, 13f, p.secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    actions.addView(w.textButton(strings.editTitle) { openEditorNow(shot) })
    actions.addView(w.textButton(strings.removeScreenshot) { sw.isChecked = false })
    detail.addView(actions)
    shotBlock = detail
    block.addView(detail)
    return block
  }

  private fun openEditorNow(shot: Bitmap) {
    openEditor(activity, w, strings, shot, selected?.boundsInWindow, edits) { next ->
      edits = next
      refresh()
    }
  }

  private fun openPointerNow() {
    val shot = screenshot ?: return
    openPointer(activity, w, strings, shot, selected, hitTest) { pick ->
      selected = pick
      refresh()
    }
  }

  private fun paintChips() {
    for ((c, chip) in chips) w.paintChip(chip, c == category)
  }

  /** The pick, as a button to make one or as its thumbnail with Change and Remove. */
  private fun paintPick() {
    val host = pickHost ?: return
    val shot = screenshot ?: return
    host.removeAllViews()
    val sel = selected
    if (sel == null) {
      host.addView(w.outlinedButton(strings.pointAtProblem) { openPointerNow() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      return
    }
    val card = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = w.rounded(p.field, 0.75f)
      setPadding(w.dp(8f), w.dp(8f), w.dp(4f), w.dp(8f))
    }
    val thumb = ImageView(activity).apply {
      setImageBitmap(ImageComposer.pickThumbnail(shot, sel.boundsInWindow, density))
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = w.rounded(p.sheet, 0.4f)
      clipToOutline = true
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    card.addView(thumb, LinearLayout.LayoutParams(w.dp(72f), w.dp(72f)))
    val texts = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(w.dp(12f), 0, w.dp(4f), 0)
    }
    texts.addView(w.label(strings.selectedPrefix, 12f, p.secondary))
    texts.addView(w.label(sel.summary, 14f, p.text).apply { maxLines = 2 })
    card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val buttons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    buttons.addView(w.textButton(strings.pickChange) { openPointerNow() })
    buttons.addView(w.textButton(strings.pickRemove) { selected = null; refresh() })
    card.addView(buttons)
    host.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  private fun refresh() {
    paintPick()
    val shot = screenshot
    if (shot != null && shotBlock != null) {
      shotBlock?.visibility = if (includeShot) View.VISIBLE else View.GONE
      if (includeShot) shotPreview?.setImageBitmap(ImageComposer.preview(shot, edits, selected?.boundsInWindow, density, w.dp(400f)))
    }
    val parts = ArrayList<String>()
    parts.add(strings.partMessage)
    if (selected != null) parts.add(strings.partElement)
    if (shot != null && includeShot) parts.add(strings.partScreenshot)
    if (afterCrash) parts.add(strings.partCrash)
    if (logsIncluded) parts.add(if (afterCrash) strings.partLogsPrevious else strings.partLogs)
    parts.add(strings.partDevice)
    summary.text = "${strings.willBeSent} ${parts.joinToString(", ")}."
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
      includeScreenshot = screenshot != null && includeShot,
      edits = if (includeShot) edits else emptyList(),
    )
    submit(draft) { result -> onResult(result) }
  }

  private fun onResult(result: FeedbackResult) {
    sending = false
    send.text = strings.send
    send.isEnabled = true
    send.alpha = 1f
    when (result) {
      is FeedbackResult.Sent -> confirm(if (result.known) strings.sentKnown else result.notice ?: strings.sentShort, if (result.known || result.notice != null) 2_500L else 1_500L)
      is FeedbackResult.SavedForLater -> confirm("${result.message} ${strings.savedForLater}", 2_500L)
      is FeedbackResult.Failed -> showError(result.message)
    }
  }

  /** The confirmation, in the sheet: a check and one line, announced, then the sheet closes by itself. */
  private fun confirm(text: String, holdMs: Long) {
    hideKeyboard()
    val done = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(w.dp(24f), w.dp(24f), w.dp(24f), w.dp(32f))
    }
    done.addView(CheckMark(activity, p.accent, p.onAccent), LinearLayout.LayoutParams(w.dp(56f), w.dp(56f)))
    val line = w.label(text, 17f, p.text, bold = true).apply {
      gravity = Gravity.CENTER
      setPadding(0, w.dp(14f), 0, 0)
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    done.addView(line)
    // The form goes; the handle stays so the sheet still reads as the same sheet.
    for (i in sheet.childCount - 1 downTo 1) sheet.removeViewAt(i)
    sheet.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    sheetLayout.settle(expand = false)
    if (look.animate) {
      done.alpha = 0f
      done.animate().alpha(1f).setDuration(160).start()
    }
    line.announceForAccessibilityCompat(text)
    main.postDelayed({ close() }, holdMs)
  }

  private fun hideKeyboard() {
    val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.hideSoftInputFromWindow(sheet.windowToken, 0)
  }

  private fun showError(text: String) {
    errorText.text = text
    errorText.visibility = View.VISIBLE
    (content as? ScrollView)?.post { (content as ScrollView).smoothScrollTo(0, errorText.bottom) }
  }

  /** Slides away (or simply goes, with animations off), then dismisses. */
  private fun close() {
    if (closing) return
    closing = true
    hideKeyboard()
    if (!look.animate || !dialog.isShowing) {
      if (dialog.isShowing) dialog.dismiss()
      return
    }
    sheetLayout.scrim.animate().alpha(0f).setDuration(180).start()
    sheet.animate().translationY(sheet.height.toFloat()).setDuration(200).withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
  }
}
