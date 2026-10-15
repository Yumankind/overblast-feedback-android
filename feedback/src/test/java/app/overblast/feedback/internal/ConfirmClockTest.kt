package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmClockTest {
  @Test
  fun timedToWhatItSays() {
    assertEquals(2_000L, ConfirmClock.holdFor(ConfirmClock.Kind.THANKS))
    assertEquals(4_000L, ConfirmClock.holdFor(ConfirmClock.Kind.LONG))
  }

  @Test
  fun closesByItselfAtTheEnd() {
    val c = ConfirmClock(2_000, startedAt = 1_000)
    assertEquals(2_000L, c.remaining(1_000))
    assertFalse(c.due(2_999))
    assertTrue(c.due(3_000))
  }

  @Test
  fun talkBackFocusOnDoneStopsTheClock() {
    val c = ConfirmClock(4_000, startedAt = 0)
    c.focusIn(1_000)
    assertTrue(c.paused)
    assertNull(c.remaining(60_000))
    assertFalse(c.due(60_000))
    // Focus leaves with 3 s left: the 3 s run from then.
    c.focusOut(60_000)
    assertEquals(3_000L, c.remaining(60_000))
    assertTrue(c.due(63_000))
  }

  @Test
  fun afterFocusThereIsAlwaysAMomentLeft() {
    val c = ConfirmClock(2_000, startedAt = 0)
    c.focusIn(1_900)
    c.focusOut(10_000)
    assertEquals(1_500L, c.remaining(10_000))
  }

  @Test
  fun aSecondFocusEventChangesNothing() {
    val c = ConfirmClock(4_000, startedAt = 0)
    c.focusIn(1_000)
    c.focusIn(3_500)
    c.focusOut(5_000)
    assertEquals(3_000L, c.remaining(5_000))
    c.focusOut(6_000) // no focus to leave
    assertEquals(2_000L, c.remaining(6_000))
  }

  @Test
  fun closingIsOnceWhoeverClosesFirst() {
    val c = ConfirmClock(2_000, startedAt = 0)
    assertTrue(c.close()) // Done
    assertFalse(c.close()) // the clock, a moment later
    assertFalse(c.due(5_000))
    assertNull(c.remaining(5_000))
  }
}
