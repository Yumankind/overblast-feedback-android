package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPromptGateTest {
  private class MemStore(var m: PromptMemory = PromptMemory()) : PromptStore {
    override fun load() = m
    override fun save(m: PromptMemory) {
      this.m = m
    }
  }

  private val on = ErrorPromptConfig(enabled = true, on = setOf("exception", "server_error"), minHoursBetween = 24.0)
  private val clear = PromptScene(foreground = true, typing = false, sheetOrOfferOpen = false, dialogUp = false)
  private var now = 1_000_000_000L
  private val start = now
  private val store = MemStore()

  private fun gate(s: MemStore = store) = ErrorPromptGate(s, { now }, start)

  @Test
  fun onlyTheKindsTheKeyListsAndOnlyWhenBothKeyAndAppAllow() {
    now = start + 20_000
    assertFalse(gate().onError("network", on, appAllows = true))
    assertFalse(gate().onError("exception", on.copy(enabled = false), appAllows = true))
    assertFalse(gate().onError("exception", on, appAllows = false))
    assertTrue(gate().onError("exception", on, appAllows = true))
    assertTrue(gate().onError("server_error", on, appAllows = true))
  }

  @Test
  fun notInTheFirstTenSeconds() {
    val g = gate()
    now = start + 3_000
    assertTrue(g.onError("exception", on, true))
    assertEquals(PromptStep.Wait(7_000), g.evaluate(clear, on, true))
    now = start + 10_000
    assertEquals(PromptStep.Show, g.evaluate(clear, on, true))
  }

  @Test
  fun waitsUpToThirtySecondsForTypingADialogTheSheetOrTheBackground() {
    for (blocked in listOf(
      clear.copy(typing = true), clear.copy(dialogUp = true), clear.copy(sheetOrOfferOpen = true), clear.copy(foreground = false),
    )) {
      now = start + 60_000
      val g = gate(MemStore())
      assertTrue(g.onError("exception", on, true))
      assertEquals(PromptStep.Wait(1_000), g.evaluate(blocked, on, true))
      now += 20_000
      assertTrue(g.evaluate(blocked, on, true) is PromptStep.Wait)
      now += 5_000
      assertEquals(PromptStep.Show, g.evaluate(clear, on, true))
    }
  }

  @Test
  fun givesUpAfterThirtySeconds() {
    now = start + 60_000
    val g = gate()
    g.onError("exception", on, true)
    now += 30_000
    assertEquals(PromptStep.Drop, g.evaluate(clear.copy(typing = true), on, true))
    // Dropped: nothing left to show even once the way is clear.
    assertEquals(PromptStep.Drop, g.evaluate(clear, on, true))
  }

  @Test
  fun thePatienceStartsWhenThePromptCouldFirstAppear() {
    val g = gate()
    now = start + 2_000
    g.onError("exception", on, true)
    now = start + 35_000 // 33 s after the error, but only 25 s after the first ten seconds
    assertTrue(g.evaluate(clear.copy(typing = true), on, true) is PromptStep.Wait)
    now = start + 40_000
    assertEquals(PromptStep.Drop, g.evaluate(clear.copy(typing = true), on, true))
  }

  @Test
  fun errorsWhileOneWaitsAreOnePrompt() {
    now = start + 60_000
    val g = gate()
    assertTrue(g.onError("exception", on, true))
    now += 1_000
    assertFalse(g.onError("server_error", on, true))
    assertFalse(g.onError("exception", on, true))
  }

  @Test
  fun oncePerProcess() {
    now = start + 60_000
    val g = gate()
    g.onError("exception", on, true)
    assertEquals(PromptStep.Show, g.evaluate(clear, on, true))
    g.onShown()
    now += 100 * 3_600_000L // even long after, in the same process
    assertFalse(g.onError("exception", on, true))
  }

  @Test
  fun oncePerMinHoursBetweenOnTheDevice() {
    now = start + 60_000
    gate().apply { onError("exception", on, true); evaluate(clear, on, true); onShown() }
    assertEquals(now, store.m.lastShownAt)
    // A new process, five hours later.
    now += 5 * 3_600_000L
    assertFalse(gate().onError("exception", on, true))
    assertTrue(gate().onError("exception", on.copy(minHoursBetween = 4.0), true))
    now += 20 * 3_600_000L
    assertTrue(gate().onError("exception", on, true))
  }

  @Test
  fun twoClosesInARowPauseItForSevenDays() {
    now = start + 60_000
    gate().onClosed()
    assertEquals(1, store.m.closesInARow)
    gate().onClosed()
    assertEquals(0, store.m.closesInARow)
    assertEquals(now + ErrorPromptGate.WEEK, store.m.quietUntil)
    now += 6 * 24 * 3_600_000L
    assertFalse(gate().onError("exception", on.copy(minHoursBetween = 0.0), true))
    now += 2 * 24 * 3_600_000L
    assertTrue(gate().onError("exception", on.copy(minHoursBetween = 0.0), true))
  }

  @Test
  fun aReportBreaksTheRunOfCloses() {
    gate().onClosed()
    gate().onReported()
    gate().onClosed()
    assertEquals(1, store.m.closesInARow)
    assertEquals(0L, store.m.quietUntil)
  }

  @Test
  fun turnedOffWhileWaitingDrops() {
    now = start + 60_000
    val g = gate()
    g.onError("exception", on, true)
    assertEquals(PromptStep.Drop, g.evaluate(clear, on.copy(enabled = false), true))
    assertEquals(PromptStep.Drop, g.evaluate(clear, on, true))
  }

  @Test
  fun nothingWaitingIsNothingToShow() {
    assertEquals(PromptStep.Drop, gate().evaluate(clear, on, true))
  }

  // ── Which requests count ─────────────────────────────────────────────────────────────────────

  @Test
  fun onlyTheAppsOwnHostsCountAndNeverTheDoor() {
    val hosts = listOf("api.example.com", "example.org")
    assertTrue(ErrorPromptGate.isAppHost("api.example.com", hosts, "brain.deployd.network"))
    assertTrue(ErrorPromptGate.isAppHost("eu.example.org", hosts, null))
    assertTrue(ErrorPromptGate.isAppHost("API.Example.com", hosts, null))
    assertFalse(ErrorPromptGate.isAppHost("notexample.org", hosts, null))
    assertFalse(ErrorPromptGate.isAppHost("cdn.analytics.com", hosts, null))
    assertFalse(ErrorPromptGate.isAppHost("api.example.com", emptyList(), null))
    assertFalse(ErrorPromptGate.isAppHost("brain.deployd.network", listOf("deployd.network"), "brain.deployd.network"))
    assertFalse(ErrorPromptGate.isAppHost(null, hosts, null))
  }

  @Test
  fun fiveHundredsAndSilenceCountFourHundredsDoNot() {
    assertEquals("server_error", ErrorPromptGate.kindOfRequest(500))
    assertEquals("server_error", ErrorPromptGate.kindOfRequest(503))
    assertEquals("network", ErrorPromptGate.kindOfRequest(0))
    assertNull(ErrorPromptGate.kindOfRequest(404))
    assertNull(ErrorPromptGate.kindOfRequest(422))
    assertNull(ErrorPromptGate.kindOfRequest(200))
  }

  // ── Typing ───────────────────────────────────────────────────────────────────────────────────

  @Test
  fun typingIsTheKeyboardOrATextChangeInTheLastFiveSeconds() {
    assertTrue(Typing.isTyping(10_000, imeVisible = true, lastTextChangeAt = null))
    assertTrue(Typing.isTyping(10_000, imeVisible = false, lastTextChangeAt = 9_000))
    assertTrue(Typing.isTyping(10_000, imeVisible = false, lastTextChangeAt = 5_001))
    assertFalse(Typing.isTyping(10_000, imeVisible = false, lastTextChangeAt = 5_000))
    // A field that only has focus, with no change and no keyboard, is not typing.
    assertFalse(Typing.isTyping(10_000, imeVisible = false, lastTextChangeAt = null))
  }

  @Test
  fun thePromptWaitsOutAPauseInTypingThenShows() {
    now = start + 60_000
    val g = gate()
    g.onError("exception", on, true)
    val lastKey = now
    val scene = { clear.copy(typing = Typing.isTyping(now, false, lastKey)) }
    assertTrue(g.evaluate(scene(), on, true) is PromptStep.Wait)
    now += 5_000
    assertEquals(PromptStep.Show, g.evaluate(scene(), on, true))
  }
}
