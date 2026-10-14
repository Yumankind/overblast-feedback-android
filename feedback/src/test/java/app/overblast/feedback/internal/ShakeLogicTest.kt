package app.overblast.feedback.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeLogicTest {
  private val hard = 3.5f * ShakeLogic.GRAVITY

  @Test
  fun twoSpikesCloseTogetherAreAShake() {
    val s = ShakeLogic(2.7f)
    assertFalse(s.onSample(hard, 0f, 0f, 1000))
    assertTrue(s.onSample(0f, hard, 0f, 1200))
  }

  @Test
  fun gravityAloneAndSlowSpikesAreNot() {
    val s = ShakeLogic(2.7f)
    assertFalse(s.onSample(0f, 0f, ShakeLogic.GRAVITY, 0))
    assertFalse(s.onSample(hard, 0f, 0f, 1000))
    assertFalse(s.onSample(hard, 0f, 0f, 2000)) // 1 s later: a new first spike
  }

  @Test
  fun oneShakeFiresOnce() {
    val s = ShakeLogic(2.7f)
    s.onSample(hard, 0f, 0f, 0)
    assertTrue(s.onSample(hard, 0f, 0f, 100))
    assertFalse(s.onSample(hard, 0f, 0f, 200))
    assertFalse(s.onSample(hard, 0f, 0f, 300))
    assertFalse(s.onSample(hard, 0f, 0f, 2200))
    assertTrue(s.onSample(hard, 0f, 0f, 2300))
  }
}
