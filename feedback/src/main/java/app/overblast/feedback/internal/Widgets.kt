package app.overblast.feedback.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView

// THE LIBRARY'S FEW WIDGETS, themed from one [Look]: labels, fields, three kinds of button and a chip.
// Text sizes are in sp and no text sits in a fixed height, so the person's font size is respected;
// every touch target is at least 48 dp high.
internal class Widgets(private val ctx: Context, val look: Look) {
  private val density = ctx.resources.displayMetrics.density
  val p: Palette get() = look.palette

  fun dp(v: Float): Int = (v * density + 0.5f).toInt()
  private fun corner(scale: Float = 1f): Float = dp(look.corner * scale).toFloat()

  fun label(text: CharSequence, sp: Float, color: Int = p.text, bold: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    look.face(this, bold)
  }

  fun field(hint: String, minLines: Int): EditText = EditText(ctx).apply {
    this.hint = hint
    this.minLines = minLines
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(p.text)
    setHintTextColor(p.secondary)
    look.face(this, false)
    background = GradientDrawable().apply {
      setColor(p.field)
      cornerRadius = corner(0.75f)
    }
    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
  }

  fun textButton(text: String, color: Int = p.accent, onClick: () -> Unit): TextView = label(text, 15f, color, bold = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48f)
    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    asButton(this)
  }

  fun filledButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, p.onAccent, bold = true).apply {
    gravity = Gravity.CENTER
    minWidth = dp(96f)
    minHeight = dp(48f)
    setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
    background = GradientDrawable().apply {
      setColor(p.accent)
      cornerRadius = corner(1.5f).coerceAtMost(dp(24f).toFloat())
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { if (isEnabled) onClick() }
    asButton(this)
  }

  fun outlinedButton(text: String, onClick: () -> Unit): TextView = label(text, 15f, p.accent, bold = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48f)
    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
    background = GradientDrawable().apply {
      setColor(0)
      setStroke(dp(1f), p.accent)
      cornerRadius = corner(0.75f)
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    asButton(this)
  }

  /** A choice among several: a pill that reads as a radio button to TalkBack. */
  fun chip(text: String, onClick: () -> Unit): TextView = label(text, 14f, p.text).apply {
    gravity = Gravity.CENTER
    minHeight = dp(40f)
    setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    accessibilityDelegate = object : View.AccessibilityDelegate() {
      override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = android.widget.RadioButton::class.java.name
        info.isCheckable = true
        @Suppress("DEPRECATION")
        info.isChecked = host.isSelected
      }
    }
  }

  fun paintChip(chip: TextView, on: Boolean) {
    chip.isSelected = on
    chip.setTextColor(if (on) p.onAccent else p.text)
    look.face(chip, on)
    chip.background = GradientDrawable().apply {
      cornerRadius = dp(20f).toFloat()
      setColor(if (on) p.accent else p.field)
    }
  }

  fun rounded(color: Int, scale: Float = 1f): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = corner(scale)
  }

  private fun asButton(v: View) {
    v.accessibilityDelegate = object : View.AccessibilityDelegate() {
      override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = android.widget.Button::class.java.name
      }
    }
  }
}

/** A check in a filled circle, for "Sent". */
// Built in code only, never inflated from XML, so the XML constructors would be dead weight.
@SuppressLint("ViewConstructor")
internal class CheckMark(context: Context, private val color: Int, private val onColor: Int) : View(context) {
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@CheckMark.color }
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = onColor
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val path = Path()

  init {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val s = minOf(width, height).toFloat()
    val cx = width / 2f
    val cy = height / 2f
    canvas.drawCircle(cx, cy, s / 2f, fill)
    stroke.strokeWidth = s * 0.09f
    path.reset()
    path.moveTo(cx - s * 0.22f, cy + s * 0.02f)
    path.lineTo(cx - s * 0.06f, cy + s * 0.17f)
    path.lineTo(cx + s * 0.24f, cy - s * 0.15f)
    canvas.drawPath(path, stroke)
  }
}
