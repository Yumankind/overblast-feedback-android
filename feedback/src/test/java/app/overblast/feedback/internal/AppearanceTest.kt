package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
  @Test fun explicitDarkWinsOverALightActivity() {
    assertTrue(Appearance.resolve(true) { false })
  }

  @Test fun explicitLightWinsOverADarkActivity() {
    assertFalse(Appearance.resolve(false) { true })
  }

  @Test fun anExplicitAnswerDoesNotReadTheActivity() {
    var read = false
    Appearance.resolve(true) { read = true; false }
    assertFalse(read)
  }

  @Test fun nullFollowsTheActivity() {
    assertTrue(Appearance.resolve(null) { true })
    assertFalse(Appearance.resolve(null) { false })
  }

  @Test fun theThreeArgumentThemeFollowsTheActivityAsIn020() {
    assertNull(FeedbackTheme().dark)
    assertNull(FeedbackTheme(accent = 0xFF6D28D9.toInt(), typeface = null, cornerRadiusDp = 20f).dark)
  }

  @Test fun theThemeCarriesAnExplicitAppearance() {
    val t = FeedbackTheme(accent = 0x123456, cornerRadiusDp = 12f, dark = true)
    assertEquals(true, t.dark)
    assertEquals(0x123456, t.accent)
    assertEquals(12f, t.cornerRadiusDp, 0f)
    assertEquals(false, FeedbackTheme(dark = false).dark)
  }

  @Test fun withDarkKeepsTheBrandAndChangesOnlyTheAppearance() {
    val base = FeedbackTheme(accent = 0x123456, cornerRadiusDp = 8f)
    val dark = base.withDark(true)
    assertEquals(true, dark.dark)
    assertEquals(0x123456, dark.accent)
    assertEquals(8f, dark.cornerRadiusDp, 0f)
    assertNull(dark.withDark(null).dark)
  }

  @Test fun eachPaletteKeepsItsTextReadableOnItsSurfaces() {
    for (dark in listOf(true, false)) {
      val p = Palette(dark)
      assertTrue("text on sheet, dark=$dark", ColorMath.contrast(p.text, p.sheet) >= 7.0)
      assertTrue("text on field, dark=$dark", ColorMath.contrast(p.text, p.field) >= 7.0)
      assertTrue("secondary on sheet, dark=$dark", ColorMath.contrast(p.secondary, p.sheet) >= 4.5)
      assertTrue("on-accent on accent, dark=$dark", ColorMath.contrast(p.onAccent, p.accent) >= 4.5)
    }
  }
}
