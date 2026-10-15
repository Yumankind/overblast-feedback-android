package app.overblast.feedback.compose

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import app.overblast.feedback.FeedbackTheme

/**
 * The feedback library's brand from the current Material 3 theme: `colorScheme.primary` as the
 * accent, `typography.bodyLarge`'s font family as the typeface, and `shapes.large`'s top-start corner
 * as the corner radius. Read it where the theme is in scope and pass it to `FeedbackOptions`:
 *
 * ```kotlin
 * val theme = FeedbackTheme.fromMaterialTheme()
 * LaunchedEffect(theme.accent) { OverblastFeedback.setTheme(theme) }
 * ```
 *
 * Needs `androidx.compose.material3` in the app, which every app with a MaterialTheme has.
 */
@Composable
public fun FeedbackTheme.Companion.fromMaterialTheme(): FeedbackTheme {
  val density = LocalDensity.current
  val resolver = LocalFontFamilyResolver.current
  val accent = MaterialTheme.colorScheme.primary.toArgb()
  val family = MaterialTheme.typography.bodyLarge.fontFamily
  val typeface = family?.let { runCatching { resolver.resolve(it).value as? Typeface }.getOrNull() }
  val corner = runCatching {
    MaterialTheme.shapes.large.topStart.toPx(Size(1_000f, 1_000f), density) / density.density
  }.getOrDefault(16f)
  return FeedbackTheme(accent = accent, typeface = typeface, cornerRadiusDp = corner)
}
