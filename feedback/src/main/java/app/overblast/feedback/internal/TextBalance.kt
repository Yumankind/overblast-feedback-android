package app.overblast.feedback.internal

import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.widget.TextView

// SHORT CENTRED MESSAGES BREAK IN BALANCED LINES: "Thanks. We already know about this one / and we're
// on it." rather than a long line and "it." alone. Balanced breaking (API 23, below our minSdk) evens
// the lines out; on API 33 and later, phrase-based word breaking also keeps a short phrase together.
// The confirmation, the error prompt, the pointer's hint, the editor's hint and the crash question
// use it; running text (the form, the summary line) keeps the platform's own breaking.
internal object TextBalance {
  /** What [apply] sets on a given Android version: the break strategy, and the word style or null where there is none. */
  fun settingsFor(sdk: Int): Pair<Int, Int?> =
    Layout.BREAK_STRATEGY_BALANCED to (if (sdk >= 33) LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE else null)

  fun apply(view: TextView, sdk: Int = Build.VERSION.SDK_INT) {
    val (strategy, wordStyle) = settingsFor(sdk)
    view.breakStrategy = strategy
    if (wordStyle != null && Build.VERSION.SDK_INT >= 33) view.lineBreakWordStyle = wordStyle
  }
}
