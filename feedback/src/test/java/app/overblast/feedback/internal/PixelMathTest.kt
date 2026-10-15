package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PixelMathTest {
  private val white = ColorMath.WHITE
  private val black = ColorMath.BLACK
  private val sheetLight = ColorMath.WHITE
  private val sheetDark = ColorMath.rgb(0x1C, 0x1C, 0x1E)
  private val fallback = ColorMath.rgb(0x25, 0x63, 0xEB)

  // ── Theme ────────────────────────────────────────────────────────────────────────────────────

  @Test
  fun contrastIsWcags() {
    assertEquals(21.0, ColorMath.contrast(white, black), 0.01)
    assertEquals(1.0, ColorMath.contrast(white, white), 0.0)
    assertEquals(4.48, ColorMath.contrast(ColorMath.rgb(0x77, 0x77, 0x77), white), 0.05)
  }

  @Test
  fun textOnTheAccentIsWhicheverReadsBetter() {
    assertEquals(white, ColorMath.onColor(ColorMath.rgb(0x25, 0x63, 0xEB))) // blue
    assertEquals(white, ColorMath.onColor(ColorMath.rgb(0x6D, 0x28, 0xD9))) // violet
    assertEquals(black, ColorMath.onColor(ColorMath.rgb(0xFA, 0xCC, 0x15))) // yellow
    assertEquals(black, ColorMath.onColor(ColorMath.rgb(0x7D, 0xD3, 0xFC))) // light sky
  }

  @Test
  fun anExplicitAccentWinsAndIsMadeOpaque() {
    val violet = ColorMath.rgb(0x6D, 0x28, 0xD9)
    assertEquals(violet, ThemeMath.resolveAccent(violet, listOf(fallback), sheetLight, fallback))
    // 0xRRGGBB, no alpha: meant opaque.
    assertEquals(violet, ThemeMath.resolveAccent(violet and 0x00FFFFFF, emptyList(), sheetLight, fallback))
    // Even a faint one: the integrator chose it.
    assertEquals(ColorMath.rgb(0xF0, 0xF0, 0xF0), ThemeMath.resolveAccent(ColorMath.rgb(0xF0, 0xF0, 0xF0), emptyList(), sheetLight, fallback))
  }

  @Test
  fun theThemesColoursInOrderSkippingInvisibleOnes() {
    val teal = ColorMath.rgb(0x00, 0x96, 0x88)
    val pink = ColorMath.rgb(0xE9, 0x1E, 0x63)
    assertEquals(teal, ThemeMath.resolveAccent(null, listOf(teal, pink), sheetLight, fallback))
    assertEquals(pink, ThemeMath.resolveAccent(null, listOf(null, pink), sheetLight, fallback))
    // A white colorPrimary on a white sheet would vanish: the next one is used.
    assertEquals(pink, ThemeMath.resolveAccent(null, listOf(white, pink), sheetLight, fallback))
    // A black primary is fine on light, not on dark.
    assertEquals(black, ThemeMath.resolveAccent(null, listOf(black), sheetLight, fallback))
    assertEquals(fallback, ThemeMath.resolveAccent(null, listOf(black), sheetDark, fallback))
    // Translucent theme colours are not an accent.
    assertEquals(fallback, ThemeMath.resolveAccent(null, listOf(ColorMath.argb(0x80, 0, 0x96, 0x88)), sheetLight, fallback))
    assertEquals(fallback, ThemeMath.resolveAccent(null, emptyList(), sheetLight, fallback))
  }

  @Test
  fun cornersStayInReason() {
    assertEquals(16f, ThemeMath.cornerRadius(16f))
    assertEquals(0f, ThemeMath.cornerRadius(-4f))
    assertEquals(32f, ThemeMath.cornerRadius(200f))
    assertEquals(16f, ThemeMath.cornerRadius(Float.NaN))
  }

  // ── The pick's thumbnail ─────────────────────────────────────────────────────────────────────

  private fun inside(c: IBox, w: Int, h: Int) = c.left >= 0 && c.top >= 0 && c.right <= w && c.bottom <= h

  private fun contains(c: IBox, b: FBox) = c.left <= b.left && c.top <= b.top && c.right >= b.right && c.bottom >= b.bottom

  @Test
  fun aSmallPickIsShownInItsPlaceAtTheMinimumSize() {
    val box = FBox(500f, 1000f, 580f, 1040f)
    val c = PixelMath.thumbnailCrop(1080, 2400, box, minSide = 360)
    assertEquals(360, c.width)
    assertEquals(360, c.height)
    assertTrue(contains(c, box))
    // Centred on the pick.
    assertEquals(540f, (c.left + c.right) / 2f, 1f)
    assertEquals(1020f, (c.top + c.bottom) / 2f, 1f)
  }

  @Test
  fun aLargePickIsPaddedByHalfItsSize() {
    val box = FBox(200f, 800f, 600f, 1000f) // 400 x 200
    val c = PixelMath.thumbnailCrop(1080, 2400, box, minSide = 100)
    assertEquals(800, c.width)
    assertEquals(400, c.height)
    assertTrue(contains(c, box))
  }

  @Test
  fun nearAnEdgeTheCropMovesInsteadOfBeingCut() {
    val box = FBox(0f, 0f, 40f, 40f)
    val c = PixelMath.thumbnailCrop(1080, 2400, box, minSide = 360)
    assertEquals(IBox(0, 0, 360, 360), c)
    val br = PixelMath.thumbnailCrop(1080, 2400, FBox(1060f, 2380f, 1080f, 2400f), minSide = 360)
    assertEquals(IBox(720, 2040, 1080, 2400), br)
  }

  @Test
  fun aPickWiderThanTheScreenIsTheScreensWidth() {
    val box = FBox(0f, 100f, 1080f, 300f)
    val c = PixelMath.thumbnailCrop(1080, 2400, box, minSide = 360)
    assertEquals(0, c.left)
    assertEquals(1080, c.right)
    assertTrue(inside(c, 1080, 2400))
  }

  @Test
  fun everyCropIsInsideTheImageAndHoldsThePick() {
    val rnd = java.util.Random(3)
    repeat(500) {
      val w = 200 + rnd.nextInt(2000)
      val h = 200 + rnd.nextInt(3000)
      val l = rnd.nextInt(w - 1).toFloat()
      val t = rnd.nextInt(h - 1).toFloat()
      val box = FBox(l, t, (l + 1 + rnd.nextInt(w - l.toInt())).coerceAtMost(w.toFloat()), (t + 1 + rnd.nextInt(h - t.toInt())).coerceAtMost(h.toFloat()))
      val c = PixelMath.thumbnailCrop(w, h, box, minSide = minOf(w, h) / 3)
      assertTrue("$c in $w x $h", inside(c, w, h))
      assertTrue("$c holds $box", contains(IBox(c.left - 1, c.top - 1, c.right + 1, c.bottom + 1), box))
    }
  }

  @Test
  fun theBoxInTheCropsOwnCoordinates() {
    assertEquals(FBox(20f, 30f, 60f, 70f), PixelMath.inCrop(FBox(120f, 230f, 160f, 270f), IBox(100, 200, 400, 500)))
  }

  // ── Cover and blur ───────────────────────────────────────────────────────────────────────────

  private fun checker(w: Int, h: Int): IntArray = IntArray(w * h) { i -> if (((i % w) + (i / w)) % 2 == 0) white else black }

  @Test
  fun coverPaintsExactlyItsBox() {
    val w = 20
    val h = 10
    val px = checker(w, h)
    val before = px.copyOf()
    PixelMath.cover(px, w, h, IBox(5, 2, 9, 6), EditMath.COVER)
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      if (x in 5 until 9 && y in 2 until 6) assertEquals(EditMath.COVER, px[i]) else assertEquals(before[i], px[i])
    }
  }

  @Test
  fun coverAndBlurAreCutToTheImage() {
    val px = checker(10, 10)
    PixelMath.cover(px, 10, 10, IBox(-5, -5, 3, 3), EditMath.COVER)
    assertEquals(EditMath.COVER, px[0])
    assertEquals(EditMath.COVER, px[2 * 10 + 2])
    PixelMath.blur(px, 10, 10, IBox(8, 8, 50, 50))
    PixelMath.cover(px, 10, 10, IBox(20, 20, 30, 30), EditMath.COVER) // entirely outside: nothing
  }

  @Test
  fun blurTakesTheDetailAndLeavesTheRest() {
    val w = 120
    val h = 80
    val px = checker(w, h)
    val before = px.copyOf()
    val box = IBox(24, 12, 96, 72)
    PixelMath.blur(px, w, h, box)
    var min = 255
    var max = 0
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      if (x in box.left until box.right && y in box.top until box.bottom) {
        val v = ColorMath.r(px[i])
        min = minOf(min, v)
        max = maxOf(max, v)
      } else {
        assertEquals(before[i], px[i])
      }
    }
    // A black-and-white checkerboard becomes a flat mid grey: no detail left to read.
    assertTrue("range $min..$max", max - min <= 4)
    assertTrue(abs((min + max) / 2 - 127) <= 3)
  }

  @Test
  fun blurKeepsAFlatColourFlat() {
    val c = ColorMath.rgb(0x40, 0x80, 0xC0)
    val px = IntArray(50 * 50) { c }
    PixelMath.blur(px, 50, 50, IBox(0, 0, 50, 50))
    assertTrue(px.all { it == c })
  }

  @Test
  fun blurIsAlwaysCoarseEnough() {
    assertEquals(12, PixelMath.blurBlock(IBox(0, 0, 30, 30)))
    assertEquals(50, PixelMath.blurBlock(IBox(0, 0, 300, 900)))
  }

  @Test
  fun editsApplyInOrderAndAtScale() {
    val w = 40
    val h = 40
    val px = checker(w, h)
    // Drawn on a 400 x 400 original, applied to a 40 x 40 preview.
    val edits = listOf(ImageEdit(FBox(0f, 0f, 200f, 400f), ImageEdit.Mode.BLUR), ImageEdit(FBox(100f, 100f, 200f, 200f), ImageEdit.Mode.COVER))
    EditMath.apply(px, w, h, edits, scale = 0.1f)
    assertEquals(EditMath.COVER, px[15 * w + 15]) // the cover drawn last is on top of the blur
    assertEquals(ColorMath.r(px[30 * w + 5]), ColorMath.r(px[31 * w + 5]), 6f) // blurred
    assertEquals(white, px[0 * w + 30]) // the right half is untouched
    assertEquals(black, px[0 * w + 31])
  }

  @Test
  fun aDragIsABoxInEitherDirectionOrNothingWhenTiny() {
    assertEquals(FBox(10f, 20f, 50f, 80f), EditMath.dragBox(50f, 80f, 10f, 20f, minSide = 8f))
    assertNull(EditMath.dragBox(10f, 10f, 14f, 60f, minSide = 8f))
  }

  private fun assertEquals(expected: Int, actual: Int, delta: Float) = assertEquals(expected.toFloat(), actual.toFloat(), delta)
}
