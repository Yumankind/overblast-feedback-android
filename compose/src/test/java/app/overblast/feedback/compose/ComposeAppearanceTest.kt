package app.overblast.feedback.compose

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeAppearanceTest {
  @Test fun nightIsDark() {
    assertEquals(true, nightOf(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL))
  }

  @Test fun notNightIsLight() {
    assertEquals(false, nightOf(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL))
  }

  @Test fun anUndefinedNightFollowsTheActivity() {
    assertNull(nightOf(Configuration.UI_MODE_NIGHT_UNDEFINED or Configuration.UI_MODE_TYPE_NORMAL))
  }
}
