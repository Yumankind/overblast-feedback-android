package app.overblast.feedback.internal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import app.overblast.feedback.OverblastPrivateRegions
import app.overblast.feedback.isOverblastPrivate
import java.io.ByteArrayOutputStream

// THE FROZEN SCREENSHOT. Taken before the sheet opens, so it shows the screen the reporter was looking
// at, and blanked before anyone sees it: views marked `overblastPrivate()`, Compose areas marked
// `Modifier.overblastPrivate()`, password fields and card-number fields become solid boxes in the
// bitmap itself. Nothing unblanked is ever kept.
internal object ScreenCapture {
  private const val MAX_SIDE = 2400
  private const val TARGET_BYTES = 4_500_000

  /** A window marked FLAG_SECURE is one the app has said must never be captured, so it is not. */
  fun isSecure(activity: Activity): Boolean =
    (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  fun capture(activity: Activity, done: (Bitmap?) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    if (isSecure(activity)) {
      main.post { done(null) }
      return
    }
    val decor = activity.window.decorView
    val w = decor.width
    val h = decor.height
    if (w <= 0 || h <= 0) {
      main.post { done(null) }
      return
    }
    val bitmap = try {
      Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (e: OutOfMemoryError) {
      main.post { done(null) }
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        PixelCopy.request(activity.window, bitmap, { result ->
          if (result == PixelCopy.SUCCESS) {
            done(mask(decor, bitmap))
          } else {
            done(drawFallback(decor, bitmap))
          }
        }, main)
        return
      } catch (e: IllegalArgumentException) {
        // No surface yet (the window is being torn down); the software draw below still works.
      }
    }
    main.post { done(drawFallback(decor, bitmap)) }
  }

  private fun drawFallback(decor: View, bitmap: Bitmap): Bitmap? = try {
    bitmap.eraseColor(Color.WHITE)
    decor.draw(Canvas(bitmap))
    mask(decor, bitmap)
  } catch (e: RuntimeException) {
    null
  }

  private fun mask(decor: View, bitmap: Bitmap): Bitmap {
    val rects = ArrayList<RectF>()
    collectPrivate(decor, rects)
    rects.addAll(OverblastPrivateRegions.boundsUnder(decor))
    if (rects.isEmpty()) return bitmap
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
      color = Color.rgb(0x3A, 0x3A, 0x3C)
      style = Paint.Style.FILL
    }
    for (r in rects) canvas.drawRect(r, paint)
    return bitmap
  }

  private fun collectPrivate(view: View, out: MutableList<RectF>) {
    if (view.visibility != View.VISIBLE) return
    if (isPrivate(view)) {
      val loc = IntArray(2)
      view.getLocationInWindow(loc)
      out.add(RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat()))
      return
    }
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) collectPrivate(view.getChildAt(i), out)
    }
  }

  /** Marked private, a password field, or a field whose autofill hints say it holds card details. */
  fun isPrivate(view: View): Boolean {
    if (view.isOverblastPrivate) return true
    if (view is TextView && isPasswordOrCard(view)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val hints = view.autofillHints
      if (hints != null && hints.any { isSensitiveHint(it) }) return true
    }
    return false
  }

  private fun isPasswordOrCard(v: TextView): Boolean {
    val type = v.inputType
    val cls = type and InputType.TYPE_MASK_CLASS
    val variation = type and InputType.TYPE_MASK_VARIATION
    if (cls == InputType.TYPE_CLASS_TEXT && (
        variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    ) {
      return true
    }
    if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
    return v.transformationMethod is PasswordTransformationMethod
  }

  private fun isSensitiveHint(hint: String): Boolean {
    val h = hint.lowercase()
    return h.contains("password") || h.startsWith("creditcard") || h.contains("cardnumber") ||
      h.contains("securitycode") || h.contains("cvc") || h.contains("cvv") || h == "otp" || h.contains("smsotp")
  }

  /** A copy of [source] with [box] (window pixels) outlined, for the image that is sent. */
  fun highlighted(source: Bitmap, box: RectF, density: Float): Bitmap {
    val copy = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(copy)
    val stroke = (3f * density).coerceAtLeast(2f)
    val fill = Paint().apply {
      color = Color.argb(0x33, 0xFF, 0x5A, 0x1F)
      style = Paint.Style.FILL
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0xFF, 0x5A, 0x1F)
      style = Paint.Style.STROKE
      strokeWidth = stroke
    }
    canvas.drawRect(box, fill)
    val inset = RectF(box)
    inset.inset(stroke / 2f, stroke / 2f)
    canvas.drawRect(inset, line)
    return copy
  }

  /** JPEG, long side at most 2400 px, under 5 MB: quality steps down first, then the size. */
  fun encodeJpeg(source: Bitmap): ByteArray? {
    var bitmap = scaleToFit(source, MAX_SIDE)
    try {
      var quality = 80
      while (true) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        if (bytes.size <= TARGET_BYTES) return bytes
        if (quality > 50) {
          quality -= 10
        } else {
          val longSide = maxOf(bitmap.width, bitmap.height)
          if (longSide < 400) return null
          val smaller = scaleToFit(bitmap, (longSide * 0.75f).toInt())
          if (bitmap !== source) bitmap.recycle()
          bitmap = smaller
          quality = 70
        }
      }
    } finally {
      if (bitmap !== source) bitmap.recycle()
    }
  }

  private fun scaleToFit(b: Bitmap, maxSide: Int): Bitmap {
    val longSide = maxOf(b.width, b.height)
    if (longSide <= maxSide) return b
    val scale = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
  }
}
