package app.overblast.feedback

import android.graphics.Typeface

/**
 * The library's brand: one accent colour, an optional typeface, a corner radius and, when the app
 * knows better than its activity, light or dark. Used by the sheet, its chips and buttons, the pointer
 * and editor screens, the error prompt and the crash question.
 *
 * Every field has a default. With no [accent], the activity theme's `colorPrimary` is used (AppCompat
 * and Material Components set it), then `android:colorPrimary`, then `android:colorAccent`; a colour
 * that cannot be seen on the sheet (white on white) is skipped. With no [typeface], the platform's.
 * With no [dark], light or dark follows the activity's own theme.
 *
 * ```kotlin
 * FeedbackOptions(theme = FeedbackTheme(accent = 0xFF6D28D9.toInt(), cornerRadiusDp = 20f))
 * FeedbackOptions(theme = FeedbackTheme(accent = brand, dark = settings.darkLook))
 * ```
 *
 * In Compose, `FeedbackTheme.fromMaterialTheme()` from the `:compose-material3` module reads
 * MaterialTheme's primary colour, shapes and light or dark, and `withComposeAppearance()` from
 * `:compose` takes light or dark from Compose's own configuration.
 *
 * @property accent an ARGB colour; `0xRRGGBB` without alpha is taken as opaque.
 * @property typeface the face for every word the library shows; bold text uses its bold style.
 * @property cornerRadiusDp the sheet's top corners and the fields' and buttons' rounding, 0 to 32.
 */
// The primary constructor keeps exactly its 0.2.0 shape, and [dark] comes in through a second
// constructor rather than a fourth defaulted parameter. A defaulted parameter would have been source
// compatible, but Kotlin callers compiled against 0.2.0 call the hidden constructor that fills in
// defaults, whose signature grows with every parameter, so an app built against 0.2.0 with a library
// that pulled in 0.2.1 would have failed with NoSuchMethodError. [dark] has no default in the second
// constructor so that a call naming it reaches only that one and a call that does not reaches only the
// primary, which keeps overload resolution unambiguous.
public class FeedbackTheme @JvmOverloads constructor(
  public val accent: Int? = null,
  public val typeface: Typeface? = null,
  public val cornerRadiusDp: Float = 16f,
) {
  /**
   * Dark (true) or light (false) for everything the library draws, whatever the activity's theme
   * says; null follows the activity, as 0.2.0 always did. For an app whose own light or dark is not
   * its activity's: one that overrides Compose's configuration, or keeps an in-app appearance setting.
   */
  public var dark: Boolean? = null
    private set

  /** As the primary constructor, with an explicit light or dark ([dark] true is dark, null follows the activity). */
  public constructor(
    accent: Int? = null,
    typeface: Typeface? = null,
    cornerRadiusDp: Float = 16f,
    dark: Boolean?,
  ) : this(accent, typeface, cornerRadiusDp) {
    this.dark = dark
  }

  /** This brand with [dark] in place of its own light or dark; null follows the activity. */
  public fun withDark(dark: Boolean?): FeedbackTheme = FeedbackTheme(accent, typeface, cornerRadiusDp, dark)

  public companion object
}
