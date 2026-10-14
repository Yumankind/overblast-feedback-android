package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class AnchorBuilderTest {
  @Test
  fun boundsBecomeFractionsAndViewportIsInDp() {
    val e = AnchorBuilder.build(listOf("DecorView", "Button"), "Button", "remove", "  Remove\n item ", 600f, 1000f, 300f, 100f, 1200, 2400, 3f)
    assertEquals(FractionRect(0.5, 0.4167, 0.25, 0.0417), e.rect)
    assertEquals(ElementViewport(400, 800), e.viewport)
    assertEquals("Remove item", e.label)
  }

  @Test
  fun aBoxOffScreenIsClippedToTheWindow() {
    val e = AnchorBuilder.build(emptyList(), "", null, null, -100f, 2300f, 400f, 500f, 1000, 2400, 2f)
    assertEquals(0.0, e.rect.x, 0.0)
    assertEquals(0.3, e.rect.w, 1e-9)
    assertEquals(2300.0 / 2400.0, e.rect.y, 1e-4)
    assertEquals(100.0 / 2400.0, e.rect.h, 1e-4)
    assertEquals("View", e.type)
  }

  @Test
  fun pathIsTwelveStepsOfAtMostEighty() {
    val e = AnchorBuilder.build((1..15).map { "S$it" + "z".repeat(90) }, "T", null, null, 0f, 0f, 1f, 1f, 10, 10, 1f)
    assertEquals(12, e.path.size)
    assertEquals(80, e.path.first().length)
    assertEquals("S4", e.path.first().take(2))
  }

  @Test
  fun zeroSizedWindowDoesNotDivideByZero() {
    val e = AnchorBuilder.build(listOf("A"), "A", null, null, 5f, 5f, 5f, 5f, 0, 0, 0f)
    assertEquals(ElementViewport(1, 1), e.viewport)
  }
}
