package app.overblast.feedback

import android.graphics.Typeface

/**
 * The library's brand: one accent colour, an optional typeface and a corner radius, used by the sheet,
 * its chips and buttons, the pointer and editor screens, the error prompt and the crash question.
 * Light or dark follows the activity's own theme.
 *
 * Every field has a default. With no [accent], the activity theme's `colorPrimary` is used (AppCompat
 * and Material Components set it), then `android:colorPrimary`, then `android:colorAccent`; a colour
 * that cannot be seen on the sheet (white on white) is skipped. With no [typeface], the platform's.
 *
 * ```kotlin
 * FeedbackOptions(theme = FeedbackTheme(accent = 0xFF6D28D9.toInt(), cornerRadiusDp = 20f))
 * ```
 *
 * In Compose, `FeedbackTheme.fromMaterialTheme()` from the `:compose` module reads MaterialTheme's
 * primary colour and shapes.
 *
 * @property accent an ARGB colour; `0xRRGGBB` without alpha is taken as opaque.
 * @property typeface the face for every word the library shows; bold text uses its bold style.
 * @property cornerRadiusDp the sheet's top corners and the fields' and buttons' rounding, 0 to 32.
 */
public class FeedbackTheme @JvmOverloads constructor(
  public val accent: Int? = null,
  public val typeface: Typeface? = null,
  public val cornerRadiusDp: Float = 16f,
) {
  public companion object
}
