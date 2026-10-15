package app.overblast.feedback.internal

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// THE BOTTOM SHEET, in plain Views: a scrim and a sheet that opens half way and drags up to full height
// (or down to close) by its handle. The sheet's HEIGHT changes, not its position, so whatever sits at
// its bottom (the Send bar) is always on screen, and the sheet always ends above the keyboard or the
// navigation bar, whichever is taller. When the keyboard comes up the sheet goes to full height, so the
// message and Send are both in view.

// Built in code only, never inflated from XML, so the XML constructors would be dead weight.
@SuppressLint("ViewConstructor")
internal class SheetLayout(
  context: Context,
  private val look: Look,
  private val onDismiss: () -> Unit,
) : FrameLayout(context) {
  private val density = resources.displayMetrics.density
  private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

  val scrim: View = View(context).apply {
    setBackgroundColor(look.palette.scrim)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    setOnClickListener { onDismiss() }
  }
  private var sheet: View? = null

  private var topInset = 0
  private var bottomInset = 0
  private var imeVisible = false
  /** The sheet's height in px; -1 until the first layout, which opens it half way. */
  private var sheetHeight = -1
  var expanded: Boolean = false
    private set
  private var animator: ValueAnimator? = null
  var onExpandedChanged: ((Boolean) -> Unit)? = null

  init {
    addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    // The insets have to arrive here even when a parent would have consumed them.
    fitsSystemWindows = false
  }

  fun setSheet(view: View) {
    sheet?.let { removeView(it) }
    sheet = view
    addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
  }

  private fun available(): Int = max(dp(160f), height - topInset - bottomInset - dp(12f))
  private fun halfHeight(): Int = min(available(), max(dp(380f), (available() * 0.62f).toInt()))

  @Suppress("DEPRECATION")
  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    val wasIme = imeVisible
    if (Build.VERSION.SDK_INT >= 30) {
      val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
      val ime = insets.getInsets(WindowInsets.Type.ime())
      topInset = bars.top
      bottomInset = max(bars.bottom, ime.bottom)
      imeVisible = insets.isVisible(WindowInsets.Type.ime())
    } else {
      topInset = insets.systemWindowInsetTop
      bottomInset = insets.systemWindowInsetBottom
      imeVisible = height > 0 && bottomInset > height / 6
    }
    if (imeVisible && !wasIme) settle(expand = true, animate = false)
    requestLayout()
    return insets
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val w = MeasureSpec.getSize(widthMeasureSpec)
    val h = MeasureSpec.getSize(heightMeasureSpec)
    setMeasuredDimension(w, h)
    scrim.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    val s = sheet ?: return
    val avail = max(dp(160f), h - topInset - bottomInset - dp(12f))
    if (sheetHeight < 0) sheetHeight = min(avail, max(dp(380f), (avail * 0.62f).toInt()))
    // Expanded follows the space (the keyboard coming and going), except while a finger holds it.
    if (expanded && animator == null && !dragging) sheetHeight = avail
    sheetHeight = sheetHeight.coerceIn(min(dp(120f), avail), avail)
    val sw = min(w, dp(640f))
    s.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sheetHeight, MeasureSpec.EXACTLY))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    scrim.layout(0, 0, width, height)
    val s = sheet ?: return
    val l = (width - s.measuredWidth) / 2
    val b = height - bottomInset
    s.layout(l, b - s.measuredHeight, l + s.measuredWidth, b)
  }

  /** Expanded or half way, animated unless the person has animations off. */
  fun settle(expand: Boolean, animate: Boolean = look.animate) {
    val target = if (expand) available() else halfHeight()
    val changedState = expanded != expand
    expanded = expand
    animator?.cancel()
    animator = null
    if (!animate || height == 0 || sheetHeight < 0) {
      sheetHeight = target
      requestLayout()
    } else {
      animator = ValueAnimator.ofInt(sheetHeight, target).apply {
        duration = 220
        interpolator = DecelerateInterpolator()
        addUpdateListener { sheetHeight = it.animatedValue as Int; requestLayout() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: android.animation.Animator) {
            if (animator === animation) animator = null
          }
        })
        start()
      }
    }
    if (changedState) onExpandedChanged?.invoke(expand)
  }

  fun toggle() = settle(!expanded)

  // ── Dragging by the handle ──────────────────────────────────────────────────────────────────

  private var dragging = false
  private var dragStartY = 0f
  private var dragStartHeight = 0
  private var velocity: VelocityTracker? = null

  /** Makes [handle] drag the sheet: up to expand, down to collapse, far down to close. A tap toggles. */
  @SuppressLint("ClickableViewAccessibility")
  fun dragWith(handle: View) {
    handle.setOnTouchListener { v, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          animator?.cancel()
          animator = null
          dragging = true
          dragStartY = e.rawY
          dragStartHeight = sheetHeight
          velocity?.recycle()
          velocity = VelocityTracker.obtain().also { it.addMovement(e) }
          true
        }
        MotionEvent.ACTION_MOVE -> {
          velocity?.addMovement(e)
          sheetHeight = (dragStartHeight - (e.rawY - dragStartY).toInt()).coerceIn(dp(80f), available())
          requestLayout()
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          dragging = false
          velocity?.addMovement(e)
          velocity?.computeCurrentVelocity(1000)
          val vy = velocity?.yVelocity ?: 0f
          velocity?.recycle()
          velocity = null
          val moved = abs(e.rawY - dragStartY)
          if (moved < dp(8f) && e.actionMasked == MotionEvent.ACTION_UP) {
            v.performClick()
          } else {
            release(vy)
          }
          true
        }
        else -> false
      }
    }
  }

  private fun release(vy: Float) {
    val half = halfHeight()
    val fling = dp(1000f)
    when {
      vy > fling && !expanded -> onDismiss()
      sheetHeight < half * 0.6f -> onDismiss()
      vy < -fling -> settle(true)
      vy > fling -> settle(false)
      else -> settle(sheetHeight > (half + available()) / 2)
    }
  }
}
