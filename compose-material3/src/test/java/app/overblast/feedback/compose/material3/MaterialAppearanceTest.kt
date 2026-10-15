package app.overblast.feedback.compose.material3

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialAppearanceTest {
  @Test fun materialsDarkSchemeIsDark() {
    assertTrue(isDarkScheme(darkColorScheme()))
  }

  @Test fun materialsLightSchemeIsLight() {
    assertFalse(isDarkScheme(lightColorScheme()))
  }

  @Test fun theBackgroundDecides() {
    // An app's own dark look built on the light scheme: only the background changed.
    assertTrue(isDarkScheme(lightColorScheme(background = Color(0xFF101014))))
    assertFalse(isDarkScheme(darkColorScheme(background = Color(0xFFF7F7FA))))
  }
}
