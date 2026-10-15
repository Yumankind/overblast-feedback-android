package app.overblast.feedback.internal

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// PURE PIXEL AND COLOUR MATH for the sheet: the brand accent and its readable text colour, the crop of
// a picked element's thumbnail, and the cover and blur the reporter draws over a screenshot. ARGB
// colours are plain Ints and images are IntArrays, so all of it is JVM-tested without a device; the
// Android side only moves pixels in and out of Bitmaps.

/** A rectangle in float pixel coordinates, left/top inclusive. */
internal data class FBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
}

/** A rectangle in whole pixels, left/top inclusive, right/bottom exclusive. */
internal data class IBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  val isEmpty: Boolean get() = width <= 0 || height <= 0
}

internal object ColorMath {
  fun a(c: Int) = (c ushr 24) and 0xff
  fun r(c: Int) = (c shr 16) and 0xff
  fun g(c: Int) = (c shr 8) and 0xff
  fun b(c: Int) = c and 0xff
  fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
  fun rgb(r: Int, g: Int, b: Int): Int = argb(0xff, r, g, b)

  val WHITE = rgb(0xff, 0xff, 0xff)
  val BLACK = rgb(0, 0, 0)

  /** WCAG relative luminance, 0 (black) to 1 (white). */
  fun luminance(c: Int): Double {
    fun ch(v: Int): Double {
      val s = v / 255.0
      return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
  }

  /** WCAG contrast ratio, 1 to 21. */
  fun contrast(x: Int, y: Int): Double {
    val lx = luminance(x)
    val ly = luminance(y)
    return (max(lx, ly) + 0.05) / (min(lx, ly) + 0.05)
  }

  /** White or black, whichever reads better on [background]. */
  fun onColor(background: Int): Int = if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK
}

internal object ThemeMath {
  /** Below this an accent is invisible on the sheet (a white brand on a white sheet) and is not used. */
  const val MIN_ACCENT_CONTRAST = 1.6

  /**
   * The accent: the app's explicit one, else the first of the theme's colours (colorPrimary, then
   * colorAccent) that is opaque and can be seen on [sheet], else [fallback]. An explicit accent is
   * used as given even when it is faint: the integrator chose it.
   */
  fun resolveAccent(explicit: Int?, themeColors: List<Int?>, sheet: Int, fallback: Int): Int {
    // A colour given without alpha (0xRRGGBB) is meant opaque.
    if (explicit != null) return if (ColorMath.a(explicit) == 0) explicit or (0xff shl 24) else explicit
    for (c in themeColors) {
      if (c == null || ColorMath.a(c) < 0xff) continue
      if (ColorMath.contrast(c, sheet) >= MIN_ACCENT_CONTRAST) return c
    }
    return fallback
  }

  /** The corner radius in dp, kept to what a sheet can wear: square to fully round within reason. */
  fun cornerRadius(dp: Float): Float = if (dp.isNaN()) 16f else dp.coerceIn(0f, 32f)
}

internal object PixelMath {
  /** [box] in whole pixels, cut to a [w]×[h] image. */
  fun clamp(box: FBox, w: Int, h: Int): IBox = IBox(
    floor(box.left).toInt().coerceIn(0, w),
    floor(box.top).toInt().coerceIn(0, h),
    ceil(box.right).toInt().coerceIn(0, w),
    ceil(box.bottom).toInt().coerceIn(0, h),
  )

  /**
   * THE THUMBNAIL OF A PICK: the part of a [w]×[h] screenshot round [box], padded by half the box's
   * size on every side so the element is seen in its place, at least [minSide] each way, never wider or
   * taller than the image, and moved (not cut) to stay inside it near an edge.
   */
  fun thumbnailCrop(w: Int, h: Int, box: FBox, minSide: Int, padFraction: Float = 0.5f): IBox {
    if (w <= 0 || h <= 0) return IBox(0, 0, 0, 0)
    val cx = (box.left + box.right) / 2f
    val cy = (box.top + box.bottom) / 2f
    val cw = min(w.toFloat(), max(minSide.toFloat(), box.width * (1 + 2 * padFraction)))
    val ch = min(h.toFloat(), max(minSide.toFloat(), box.height * (1 + 2 * padFraction)))
    var left = cx - cw / 2f
    var top = cy - ch / 2f
    left = left.coerceIn(0f, w - cw)
    top = top.coerceIn(0f, h - ch)
    val l = left.roundToInt().coerceIn(0, w)
    val t = top.roundToInt().coerceIn(0, h)
    return IBox(l, t, (l + cw.roundToInt()).coerceAtMost(w), (t + ch.roundToInt()).coerceAtMost(h))
  }

  /** [box] in a crop's own coordinates, for drawing the pick's outline on its thumbnail. */
  fun inCrop(box: FBox, crop: IBox): FBox = FBox(box.left - crop.left, box.top - crop.top, box.right - crop.left, box.bottom - crop.top)

  /** Paints [box] of the [w]×[h] image [px] solid [color]: the cover, which hides everything under it. */
  fun cover(px: IntArray, w: Int, h: Int, box: IBox, color: Int) {
    val r = IBox(box.left.coerceIn(0, w), box.top.coerceIn(0, h), box.right.coerceIn(0, w), box.bottom.coerceIn(0, h))
    if (r.isEmpty) return
    for (y in r.top until r.bottom) {
      val row = y * w
      for (x in r.left until r.right) px[row + x] = color
    }
  }

  /** How coarse the blur of a [box] is: a sixth of its shorter side, at least 12 px, so text under it never reads. */
  fun blurBlock(box: IBox): Int = max(12, min(box.width, box.height) / 6)

  /**
   * THE BLUR, BY DOWNSCALE AND UPSCALE: [box] is averaged down to one sample per [block]×[block]
   * square, then spread back with bilinear interpolation, so the area keeps its colours and loses its
   * detail. Pixels outside [box] are not touched.
   */
  fun blur(px: IntArray, w: Int, h: Int, box: IBox, block: Int = blurBlock(box)) {
    val r = IBox(box.left.coerceIn(0, w), box.top.coerceIn(0, h), box.right.coerceIn(0, w), box.bottom.coerceIn(0, h))
    if (r.isEmpty) return
    val bs = max(1, block)
    val sw = (r.width + bs - 1) / bs
    val sh = (r.height + bs - 1) / bs
    val small = IntArray(sw * sh)
    for (sy in 0 until sh) {
      for (sx in 0 until sw) {
        var a = 0L
        var rr = 0L
        var gg = 0L
        var bb = 0L
        var n = 0
        val x0 = r.left + sx * bs
        val y0 = r.top + sy * bs
        for (y in y0 until min(y0 + bs, r.bottom)) {
          val row = y * w
          for (x in x0 until min(x0 + bs, r.right)) {
            val c = px[row + x]
            a += ColorMath.a(c); rr += ColorMath.r(c); gg += ColorMath.g(c); bb += ColorMath.b(c)
            n++
          }
        }
        small[sy * sw + sx] = if (n == 0) 0 else ColorMath.argb((a / n).toInt(), (rr / n).toInt(), (gg / n).toInt(), (bb / n).toInt())
      }
    }
    for (y in r.top until r.bottom) {
      val fy = ((y - r.top + 0.5f) / bs - 0.5f).coerceIn(0f, (sh - 1).toFloat())
      val y0 = floor(fy).toInt()
      val y1 = min(y0 + 1, sh - 1)
      val ty = fy - y0
      val row = y * w
      for (x in r.left until r.right) {
        val fx = ((x - r.left + 0.5f) / bs - 0.5f).coerceIn(0f, (sw - 1).toFloat())
        val x0 = floor(fx).toInt()
        val x1 = min(x0 + 1, sw - 1)
        val tx = fx - x0
        px[row + x] = bilerp(small[y0 * sw + x0], small[y0 * sw + x1], small[y1 * sw + x0], small[y1 * sw + x1], tx, ty)
      }
    }
  }

  private fun bilerp(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
    fun ch(shift: Int): Int {
      val v00 = (c00 ushr shift) and 0xff
      val v10 = (c10 ushr shift) and 0xff
      val v01 = (c01 ushr shift) and 0xff
      val v11 = (c11 ushr shift) and 0xff
      val top = v00 + (v10 - v00) * tx
      val bottom = v01 + (v11 - v01) * tx
      return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
    }
    return ColorMath.argb(ch(24), ch(16), ch(8), ch(0))
  }
}

/** One area the reporter hid before sending, in screenshot pixels. */
internal data class ImageEdit(val box: FBox, val mode: Mode) {
  enum class Mode { COVER, BLUR }
}

/** The edits, applied in order, to an image held as pixels: what the Android composer does to a Bitmap. */
internal object EditMath {
  /** The colour of a cover: the private masks' own, so a hidden area looks the same whoever hid it. */
  val COVER = ColorMath.rgb(0x3A, 0x3A, 0x3C)

  fun apply(px: IntArray, w: Int, h: Int, edits: List<ImageEdit>, scale: Float = 1f) {
    for (e in edits) {
      val box = PixelMath.clamp(FBox(e.box.left * scale, e.box.top * scale, e.box.right * scale, e.box.bottom * scale), w, h)
      when (e.mode) {
        ImageEdit.Mode.COVER -> PixelMath.cover(px, w, h, box, COVER)
        ImageEdit.Mode.BLUR -> PixelMath.blur(px, w, h, box)
      }
    }
  }

  /** A drag from (x0, y0) to (x1, y1) as a box, either direction, or null when it is too small to mean anything. */
  fun dragBox(x0: Float, y0: Float, x1: Float, y1: Float, minSide: Float): FBox? {
    val b = FBox(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    return if (b.width < minSide || b.height < minSide) null else b
  }
}
