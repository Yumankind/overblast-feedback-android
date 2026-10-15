package app.overblast.feedback.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.widget.TextView
import app.overblast.feedback.FeedbackTheme

// THE RESOLVED LOOK: light or dark from the theme or else the activity, the accent from the app (or its theme), the
// typeface and the corner radius. Every screen the library draws takes one of these, so a brand set
// once in FeedbackOptions reaches the sheet, the pointer, the editor, the pill and the crash question.

internal class Palette(val dark: Boolean, accentOverride: Int? = null) {
  val sheet = if (dark) ColorMath.rgb(0x1C, 0x1C, 0x1E) else ColorMath.WHITE
  val field = if (dark) ColorMath.rgb(0x2C, 0x2C, 0x2E) else ColorMath.rgb(0xF2, 0xF2, 0xF5)
  val text = if (dark) ColorMath.rgb(0xF2, 0xF2, 0xF7) else ColorMath.rgb(0x1C, 0x1C, 0x1E)
  val secondary = if (dark) ColorMath.rgb(0xA1, 0xA1, 0xA6) else ColorMath.rgb(0x6B, 0x6B, 0x70)
  val accent = accentOverride ?: defaultAccent(dark)
  val onAccent = ColorMath.onColor(accent)
  val danger = if (dark) ColorMath.rgb(0xFF, 0x6B, 0x6B) else ColorMath.rgb(0xC6, 0x28, 0x28)
  val highlight = ColorMath.rgb(0xFF, 0x5A, 0x1F)
  val scrim = ColorMath.argb(0x99, 0, 0, 0)

  companion object {
    fun defaultAccent(dark: Boolean): Int = if (dark) ColorMath.rgb(0x5B, 0x8D, 0xFF) else ColorMath.rgb(0x25, 0x63, 0xEB)
  }
}

internal class Look(
  val palette: Palette,
  val typeface: Typeface?,
  /** In dp. */
  val corner: Float,
  /** False when the person has turned animations off (the animator scale at 0). */
  val animate: Boolean,
) {
  val dark: Boolean get() = palette.dark

  /** Sets [view]'s face, bold or not, from the brand's typeface (the platform's when there is none). */
  fun face(view: TextView, bold: Boolean) {
    val base = typeface
    view.typeface = when {
      base == null -> if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
      bold -> Typeface.create(base, Typeface.BOLD)
      else -> base
    }
  }

  companion object {
    fun of(activity: Activity, theme: FeedbackTheme): Look {
      val dark = Appearance.resolve(theme.dark) { isDark(activity) }
      val sheet = Palette(dark).sheet
      val accent = ThemeMath.resolveAccent(
        explicit = theme.accent,
        themeColors = listOf(attrColor(activity, appAttr(activity, "colorPrimary")), attrColor(activity, android.R.attr.colorPrimary), attrColor(activity, android.R.attr.colorAccent)),
        sheet = sheet,
        fallback = Palette.defaultAccent(dark),
      )
      return Look(Palette(dark, accent), theme.typeface, ThemeMath.cornerRadius(theme.cornerRadiusDp), animationsOn(activity))
    }

    /** The app's own `colorPrimary` attribute (AppCompat, Material Components), looked up by name so the library needs neither. */
    @SuppressLint("DiscouragedApi") // by name on purpose: the attribute is the app's library's, not ours
    private fun appAttr(context: Context, name: String): Int =
      context.resources.getIdentifier(name, "attr", context.packageName)

    private fun attrColor(activity: Activity, attr: Int): Int? {
      if (attr == 0) return null
      val tv = TypedValue()
      if (!activity.theme.resolveAttribute(attr, tv, true)) return null
      return when {
        tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
        tv.resourceId != 0 -> runCatching { activity.getColor(tv.resourceId) }.getOrNull()
        else -> null
      }
    }

    fun animationsOn(context: Context): Boolean {
      if (android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return false
      val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
      return scale > 0f
    }
  }
}

/**
 * LIGHT OR DARK, DECIDED ONCE. The theme's explicit [FeedbackTheme.dark] wins, because an app that
 * says it is dark knows something the activity does not: Compose lets an app override its own
 * configuration (an in-app Dark look) while the activity's resources keep the system's. Only when the
 * app says nothing does the activity decide, which is exactly what 0.2.0 did. [activityDark] is a
 * function so the activity's theme is not read when the answer is already known.
 */
internal object Appearance {
  inline fun resolve(explicit: Boolean?, activityDark: () -> Boolean): Boolean = explicit ?: activityDark()
}

/**
 * Dark when the activity's THEME is dark, which is not always the system's night mode: an app with a
 * light-only theme on a phone in dark mode is a light app. Android 10 and later themes say which they
 * are (`android:isLightTheme`, which Material, AppCompat and MDC themes all set); before that, and for a
 * theme that does not say, the activity's night mode decides.
 */
internal fun isDark(activity: Activity): Boolean {
  if (android.os.Build.VERSION.SDK_INT >= 29) {
    val tv = TypedValue()
    if (activity.theme.resolveAttribute(android.R.attr.isLightTheme, tv, true) && tv.type == TypedValue.TYPE_INT_BOOLEAN) {
      return tv.data == 0
    }
  }
  return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
