package app.overblast.feedback.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import app.overblast.feedback.FeedbackStrings

// THE TWO FULL-SCREEN TOOLS over the frozen screenshot: the pointer ("tap the part that's wrong", a
// clear highlight, and Use this to confirm) and the editor (drag to cover or blur parts before sending,
// with Undo and Clear). Both are themed from the sheet's [Look], keep clear of the system bars, and
// leave the private masks alone: those are in the screenshot's own pixels.

private val DARK = ColorMath.rgb(0x1C, 0x1C, 0x1E)
private val ON_DARK = ColorMath.rgb(0xF2, 0xF2, 0xF7)
private val ON_DARK_2 = ColorMath.rgb(0xD1, 0xD1, 0xD6)

@Suppress("DEPRECATION")
private fun fullScreen(activity: Activity, root: View): Dialog {
  val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  // Edge to edge on every Android: the bars are inset by hand below.
  root.setOnApplyWindowInsetsListener { v, insets ->
    val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
      val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
      b.top to b.bottom
    } else {
      insets.systemWindowInsetTop to insets.systemWindowInsetBottom
    }
    v.setPadding(0, top, 0, bottom)
    insets
  }
  d.setContentView(root)
  return d
}

/** The pointer. [pick] turns a tap (screenshot pixels) into an element; [done] gets the confirmed one. */
internal fun openPointer(
  activity: Activity,
  w: Widgets,
  strings: FeedbackStrings,
  shot: Bitmap,
  current: SelectedElement?,
  pick: (Float, Float) -> SelectedElement?,
  done: (SelectedElement) -> Unit,
) {
  val root = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(ColorMath.BLACK)
  }
  val dialog = fullScreen(activity, root)
  var pending: SelectedElement? = current
  lateinit var use: TextView
  lateinit var caption: TextView

  val hint = w.label(strings.pointHint, 15f, ON_DARK, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(w.dp(16f), w.dp(12f), w.dp(16f), w.dp(12f))
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    TextBalance.apply(this)
  }
  root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

  val view = PointerView(activity, shot, w.p.accent).apply {
    highlight = pending?.boundsInWindow
    contentDescription = strings.pointHint
    onTap = { x, y ->
      val hit = pick(x, y)
      if (hit != null) {
        pending = hit
        highlight = hit.boundsInWindow
        caption.text = hit.summary
        caption.announceForAccessibilityCompat("${strings.selectedPrefix} ${hit.summary}")
        use.isEnabled = true
        use.alpha = 1f
        invalidate()
      }
    }
  }
  root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

  val bar = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = w.dp(64f)
    setPadding(w.dp(16f), w.dp(8f), w.dp(12f), w.dp(8f))
    setBackgroundColor(DARK)
  }
  caption = w.label(pending?.summary ?: strings.pointNothing, 14f, ON_DARK_2).apply { maxLines = 2 }
  bar.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
  bar.addView(w.textButton(strings.cancel, ON_DARK) { dialog.dismiss() })
  use = w.filledButton(strings.pointDone) {
    pending?.let(done)
    dialog.dismiss()
  }.apply {
    isEnabled = pending != null
    alpha = if (pending != null) 1f else 0.5f
  }
  bar.addView(use, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = w.dp(8f) })
  root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  dialog.show()
}

/** The editor. [edits] are the ones kept so far; [done] gets the new list, private masks untouched. */
internal fun openEditor(
  activity: Activity,
  w: Widgets,
  strings: FeedbackStrings,
  shot: Bitmap,
  highlight: RectF?,
  edits: List<ImageEdit>,
  done: (List<ImageEdit>) -> Unit,
) {
  val root = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(ColorMath.BLACK)
  }
  val dialog = fullScreen(activity, root)
  val working = ArrayList(edits)
  var mode = ImageEdit.Mode.COVER
  val density = activity.resources.displayMetrics.density

  // Tools: Cover | Blur, then Undo and Clear.
  val tools = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(w.dp(12f), w.dp(8f), w.dp(8f), w.dp(8f))
    setBackgroundColor(DARK)
  }
  lateinit var coverChip: TextView
  lateinit var blurChip: TextView
  lateinit var undo: TextView
  lateinit var clear: TextView
  lateinit var editor: EditorView
  fun paint() {
    w.paintChip(coverChip, mode == ImageEdit.Mode.COVER)
    w.paintChip(blurChip, mode == ImageEdit.Mode.BLUR)
    val any = working.isNotEmpty()
    undo.isEnabled = any
    undo.alpha = if (any) 1f else 0.4f
    clear.isEnabled = any
    clear.alpha = if (any) 1f else 0.4f
  }
  coverChip = w.chip(strings.editCover) { mode = ImageEdit.Mode.COVER; paint() }
  blurChip = w.chip(strings.editBlur) { mode = ImageEdit.Mode.BLUR; paint() }
  tools.addView(coverChip)
  tools.addView(blurChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = w.dp(8f) })
  tools.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
  undo = w.textButton(strings.editUndo, ON_DARK) { if (working.isNotEmpty()) { working.removeAt(working.lastIndex); editor.refresh(working); paint() } }
  clear = w.textButton(strings.editClear, ON_DARK) { working.clear(); editor.refresh(working); paint() }
  tools.addView(undo)
  tools.addView(clear)
  root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

  root.addView(w.label(strings.editHint, 13f, ON_DARK_2).apply {
    TextBalance.apply(this)
    gravity = Gravity.CENTER
    setPadding(w.dp(16f), w.dp(8f), w.dp(16f), w.dp(8f))
  }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

  editor = EditorView(activity, shot, highlight, density, w.p.accent).apply {
    contentDescription = strings.editHint
    onDrawn = { box ->
      working.add(ImageEdit(box, mode))
      refresh(working)
      paint()
    }
  }
  root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

  val bar = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    minimumHeight = w.dp(64f)
    setPadding(w.dp(16f), w.dp(8f), w.dp(12f), w.dp(8f))
    setBackgroundColor(DARK)
  }
  bar.addView(w.textButton(strings.cancel, ON_DARK) { dialog.dismiss() })
  bar.addView(w.filledButton(strings.editDone) { done(ArrayList(working)); dialog.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = w.dp(8f) })
  root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  paint()
  dialog.setOnShowListener { editor.refresh(working) }
  dialog.show()
}

/** The screenshot fit to the view, with a tap reported in screenshot pixels and the pick drawn clearly: the rest dimmed, the element outlined. */
// Built in code only, never inflated from XML, so the XML constructors would be dead weight.
@SuppressLint("ViewConstructor")
internal class PointerView(context: Context, private val bitmap: Bitmap, accent: Int) : View(context) {
  var highlight: RectF? = null
  var onTap: ((Float, Float) -> Unit)? = null

  private val d = resources.displayMetrics.density
  private val dst = RectF()
  private val box = RectF()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val dim = Paint().apply { color = ColorMath.argb(0x8C, 0, 0, 0) }
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = accent
    style = Paint.Style.STROKE
    strokeWidth = 3f * d
  }
  private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ColorMath.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 6f * d
  }
  private val path = Path()

  private fun layoutBitmap() = fit(bitmap, width, height, 8f * d, dst)

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    layoutBitmap()
    canvas.drawBitmap(bitmap, null, dst, paint)
    val h = highlight ?: return
    val sx = dst.width() / bitmap.width
    val sy = dst.height() / bitmap.height
    box.set(dst.left + h.left * sx, dst.top + h.top * sy, dst.left + h.right * sx, dst.top + h.bottom * sy)
    // Everything but the pick, dimmed, so the pick stands out on any screen.
    path.reset()
    path.fillType = Path.FillType.EVEN_ODD
    path.addRect(dst, Path.Direction.CW)
    path.addRect(box, Path.Direction.CW)
    canvas.drawPath(path, dim)
    val r = 4f * d
    canvas.drawRoundRect(box, r, r, halo)
    canvas.drawRoundRect(box, r, r, stroke)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> return true
      MotionEvent.ACTION_UP -> {
        layoutBitmap()
        if (dst.contains(event.x, event.y) && dst.width() > 0) {
          onTap?.invoke((event.x - dst.left) * bitmap.width / dst.width(), (event.y - dst.top) * bitmap.height / dst.height())
        }
        performClick()
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean = super.performClick()
}

/** The editor's canvas: the screenshot with the edits applied, and the rectangle being dragged. */
// Built in code only, never inflated from XML, so the XML constructors would be dead weight.
@SuppressLint("ViewConstructor")
internal class EditorView(
  context: Context,
  private val source: Bitmap,
  private val highlight: RectF?,
  private val density: Float,
  accent: Int,
) : View(context) {
  var onDrawn: ((FBox) -> Unit)? = null

  private var preview: Bitmap? = null
  private val dst = RectF()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val drag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = accent
    style = Paint.Style.STROKE
    strokeWidth = 2f * density
  }
  private val dragFill = Paint().apply { color = ColorMath.argb(0x33, ColorMath.r(accent), ColorMath.g(accent), ColorMath.b(accent)) }
  private var x0 = 0f
  private var y0 = 0f
  private var live: RectF? = null

  fun refresh(edits: List<ImageEdit>) {
    val side = maxOf(width, height, 720)
    val next = ImageComposer.preview(source, edits, highlight, density, side)
    preview?.recycle()
    preview = next
    invalidate()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    preview?.recycle()
    preview = null
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    fit(source, width, height, 8f * density, dst)
    preview?.let { canvas.drawBitmap(it, null, dst, paint) }
    live?.let {
      canvas.drawRect(it, dragFill)
      canvas.drawRect(it, drag)
    }
  }

  override fun onTouchEvent(e: MotionEvent): Boolean {
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        x0 = e.x.coerceIn(dst.left, dst.right)
        y0 = e.y.coerceIn(dst.top, dst.bottom)
        live = RectF(x0, y0, x0, y0)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val x = e.x.coerceIn(dst.left, dst.right)
        val y = e.y.coerceIn(dst.top, dst.bottom)
        live = RectF(minOf(x0, x), minOf(y0, y), maxOf(x0, x), maxOf(y0, y))
        invalidate()
        return true
      }
      MotionEvent.ACTION_UP -> {
        val x = e.x.coerceIn(dst.left, dst.right)
        val y = e.y.coerceIn(dst.top, dst.bottom)
        live = null
        invalidate()
        if (dst.width() > 0) {
          val s = source.width / dst.width()
          EditMath.dragBox((x0 - dst.left) * s, (y0 - dst.top) * s, (x - dst.left) * s, (y - dst.top) * s, minSide = 12f * density * s)
            ?.let { onDrawn?.invoke(it) }
        }
        performClick()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        live = null
        invalidate()
      }
    }
    return super.onTouchEvent(e)
  }

  override fun performClick(): Boolean = super.performClick()
}

/** [bitmap] fit into a [w]×[h] view with [pad] round it, centred, into [out]. */
internal fun fit(bitmap: Bitmap, w: Int, h: Int, pad: Float, out: RectF) {
  val aw = w - 2 * pad
  val ah = h - 2 * pad
  if (aw <= 0 || ah <= 0 || bitmap.width == 0 || bitmap.height == 0) {
    out.setEmpty()
    return
  }
  val scale = minOf(aw / bitmap.width, ah / bitmap.height)
  val bw = bitmap.width * scale
  val bh = bitmap.height * scale
  val l = (w - bw) / 2f
  val t = (h - bh) / 2f
  out.set(l, t, l + bw, t + bh)
}

/** TalkBack, told once; `announceForAccessibility` is deprecated on new Androids in favour of live regions, which [View]s here also carry. */
@Suppress("DEPRECATION")
internal fun View.announceForAccessibilityCompat(text: CharSequence) {
  announceForAccessibility(text)
}
