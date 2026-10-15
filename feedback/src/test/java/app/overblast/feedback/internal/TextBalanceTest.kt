package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextBalanceTest {
  @Test
  fun balancedEverywhereAndPhraseBreakingFromApi33() {
    // Layout.BREAK_STRATEGY_BALANCED is 2 and LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE is 1.
    for (sdk in 24..32) {
      val (strategy, word) = TextBalance.settingsFor(sdk)
      assertEquals(2, strategy)
      assertNull(word)
    }
    for (sdk in 33..36) assertEquals(2 to 1, TextBalance.settingsFor(sdk))
  }
}
