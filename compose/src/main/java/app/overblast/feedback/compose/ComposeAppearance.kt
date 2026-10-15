package app.overblast.feedback.compose

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import app.overblast.feedback.FeedbackTheme

/**
 * This brand with light or dark taken from Compose's configuration where it is called, rather than
 * from the activity. They differ when an app gives its composition a configuration of its own (an
 * in-app Dark look provided through `LocalConfiguration`) while the activity's resources keep the
 * system's: without this the sheet would open light over a dark app. Read it inside that composition
 * and hand it to the library:
 *
 * ```kotlin
 * val theme = FeedbackTheme(accent = brand).withComposeAppearance()
 * LaunchedEffect(theme.dark) { OverblastFeedback.setTheme(theme) }
 * ```
 *
 * A configuration that does not say night or not leaves the brand as it was, following the activity.
 * With a Material 3 theme, `fromMaterialTheme()` in `:compose-material3` already sets light or dark
 * from the colour scheme and needs no more than that.
 */
@Composable
public fun FeedbackTheme.withComposeAppearance(): FeedbackTheme {
  val night = nightOf(LocalConfiguration.current.uiMode) ?: return this
  return withDark(night)
}

/** True for night, false for not night, null when [uiMode] does not say (UI_MODE_NIGHT_UNDEFINED). */
internal fun nightOf(uiMode: Int): Boolean? = when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
  Configuration.UI_MODE_NIGHT_YES -> true
  Configuration.UI_MODE_NIGHT_NO -> false
  else -> null
}
