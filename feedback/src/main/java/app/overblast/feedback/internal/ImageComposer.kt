package app.overblast.feedback.internal

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.annotation.TargetApi

// THE SCREENSHOT AS SENT: the frozen capture (private views already blanked in it, so nothing here can
// uncover them), then the reporter's covers and blurs in the order they drew them, then the picked
// element's outline. Blur is RenderEffect's on Android 12 and later, drawn through a RenderNode into a
// hardware buffer; below that, or if that path fails, the pure downscale-and-upscale blur in [PixelMath].
internal object ImageComposer {
  /** [source] with [edits] applied and [highlight] (window pixels) outlined; [source] itself is left as it is. */
  fun compose(source: Bitmap, edits: List<ImageEdit>, highlight: RectF?, density: Float): Bitmap {
    var out = source.copy(Bitmap.Config.ARGB_8888, true)
    if (edits.isNotEmpty()) out = applyEdits(out, edits, 1f)
    if (highlight != null) {
      val outlined = ScreenCapture.highlighted(out, highlight, density)
      out.recycle()
      out = outlined
    }
    return out
  }

  /** A small copy for the sheet's preview: scaled to [maxSide] first, so the edits cost little. */
  fun preview(source: Bitmap, edits: List<ImageEdit>, highlight: RectF?, density: Float, maxSide: Int): Bitmap {
    val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
    val small = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source.copy(Bitmap.Config.ARGB_8888, true)
    var out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== small) small.recycle() }
    if (edits.isNotEmpty()) out = applyEdits(out, edits, scale)
    if (highlight != null) {
      val h = RectF(highlight.left * scale, highlight.top * scale, highlight.right * scale, highlight.bottom * scale)
      val outlined = ScreenCapture.highlighted(out, h, (density * scale).coerceAtLeast(1f))
      out.recycle()
      out = outlined
    }
    return out
  }

  /** The thumbnail of a pick: the crop round [box] with the box outlined, and the crop it was cut at. */
  fun pickThumbnail(source: Bitmap, box: RectF, density: Float): Bitmap {
    val minSide = (minOf(source.width, source.height) / 3).coerceAtLeast(1)
    val crop = PixelMath.thumbnailCrop(source.width, source.height, FBox(box.left, box.top, box.right, box.bottom), minSide)
    val cut = Bitmap.createBitmap(source, crop.left, crop.top, crop.width.coerceAtLeast(1), crop.height.coerceAtLeast(1))
    val inCrop = PixelMath.inCrop(FBox(box.left, box.top, box.right, box.bottom), crop)
    val outlined = ScreenCapture.highlighted(cut.copy(Bitmap.Config.ARGB_8888, true), RectF(inCrop.left, inCrop.top, inCrop.right, inCrop.bottom), density)
    if (cut !== source) cut.recycle()
    return outlined
  }

  private fun applyEdits(bitmap: Bitmap, edits: List<ImageEdit>, scale: Float): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val canvas = Canvas(bitmap)
    val coverPaint = Paint().apply { color = EditMath.COVER; style = Paint.Style.FILL }
    for (e in edits) {
      val box = PixelMath.clamp(FBox(e.box.left * scale, e.box.top * scale, e.box.right * scale, e.box.bottom * scale), w, h)
      if (box.isEmpty) continue
      when (e.mode) {
        ImageEdit.Mode.COVER -> canvas.drawRect(Rect(box.left, box.top, box.right, box.bottom), coverPaint)
        ImageEdit.Mode.BLUR -> {
          val done = Build.VERSION.SDK_INT >= 31 && runCatching { renderEffectBlur(bitmap, canvas, box) }.getOrDefault(false)
          if (!done) pureBlur(bitmap, box)
        }
      }
    }
    return bitmap
  }

  private fun pureBlur(bitmap: Bitmap, box: IBox) {
    val px = IntArray(box.width * box.height)
    bitmap.getPixels(px, 0, box.width, box.left, box.top, box.width, box.height)
    PixelMath.blur(px, box.width, box.height, IBox(0, 0, box.width, box.height))
    bitmap.setPixels(px, 0, box.width, box.left, box.top, box.width, box.height)
  }

  /**
   * RenderEffect's blur of [box], strong enough that text under it does not read: the area is drawn
   * at a quarter size through a blur node and drawn back stretched, which is both cheaper and blurrier
   * than a large radius at full size. Returns false when the platform refused, and the caller blurs by hand.
   */
  @TargetApi(31)
  @SuppressLint("UseRequiresApi") // androidx.annotation is not a dependency; the one caller checks SDK_INT
  private fun renderEffectBlur(bitmap: Bitmap, canvas: Canvas, box: IBox): Boolean {
    val region = Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height)
    val sw = (box.width / 4).coerceAtLeast(1)
    val sh = (box.height / 4).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(region, sw, sh, true)
    if (region !== bitmap) region.recycle()
    val radius = (minOf(sw, sh) / 4f).coerceIn(4f, 60f)
    val reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val node = RenderNode("overblast-blur")
    val renderer = android.graphics.HardwareRenderer()
    try {
      node.setPosition(0, 0, sw, sh)
      node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
      val rc = node.beginRecording()
      rc.drawBitmap(small, 0f, 0f, null)
      node.endRecording()
      renderer.setSurface(reader.surface)
      renderer.setContentRoot(node)
      renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
      val image = reader.acquireNextImage() ?: return false
      image.use { img ->
        val hb = img.hardwareBuffer ?: return false
        hb.use { buf ->
          val wrapped = Bitmap.wrapHardwareBuffer(buf, null) ?: return false
          val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
          wrapped.recycle()
          val dst = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
          canvas.drawBitmap(soft, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
          soft.recycle()
        }
      }
      return true
    } finally {
      small.recycle()
      renderer.destroy()
      node.discardDisplayList()
      reader.close()
    }
  }
}
