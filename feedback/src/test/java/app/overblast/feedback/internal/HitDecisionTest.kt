package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitDecisionTest {
  private class Sem(val name: String, val id: String? = null, val label: String? = null)

  private fun plain(name: String, id: String? = null, label: String? = null) = ViewStep(name, ViewKind.PLAIN, id, label)
  private fun compose() = ViewStep("AndroidComposeView", ViewKind.COMPOSE_HOST, null, null)
  private fun handler() = ViewStep("AndroidViewsHandler", ViewKind.WRAPPER, null, null)
  private fun holder() = ViewStep("ViewFactoryHolder", ViewKind.WRAPPER, null, null)

  private fun decide(chain: List<ViewStep>, sem: Map<Int, Sem>): HitPick<Sem>? =
    HitDecision.decide(chain, { sem[it] }) { it.id != null || it.label != null }

  // Deskslap's screen: a map hosted in Compose, a tagged Compose button over it.
  private val root = listOf(plain("DecorView"), plain("LinearLayout"), plain("FrameLayout"), compose())

  @Test
  fun aTaggedComposeNodeWinsOverTheFullScreenHandler() {
    // The finger is on the Compose button, not on the map; the View walk still ends in the handler.
    val chain = root + handler()
    val pick = decide(chain, mapOf(3 to Sem("Button", id = "center_on_me")))
    assertEquals(HitPick.Semantic(3, (pick as HitPick.Semantic).hit), pick)
    assertEquals("center_on_me", pick.hit.id)
  }

  @Test
  fun aTaggedComposeNodeWinsEvenOverTheHostedMap() {
    // A testTag on the AndroidView's own modifier names it better than the MapView class.
    val chain = root + handler() + holder() + plain("MapView") + plain("TextureView")
    val pick = decide(chain, mapOf(3 to Sem("Node", id = "map"))) as HitPick.Semantic
    assertEquals("map", pick.hit.id)
  }

  @Test
  fun anUnnamedNodeOverTheMapGivesTheMapViewNotTheHandler() {
    val chain = root + handler() + holder() + plain("MapView") + plain("TextureView")
    assertEquals(HitPick.View(6), decide(chain, mapOf(3 to Sem("Node"))))
    // With no semantics at all, the same.
    assertEquals(HitPick.View(6), decide(chain, emptyMap()))
  }

  @Test
  fun aNamedViewInsideTheHostedViewIsPreferred() {
    val chain = root + handler() + holder() + plain("CameraView") + plain("ImageButton", id = "shutter")
    assertEquals(HitPick.View(7), decide(chain, emptyMap()))
  }

  @Test
  fun anUnnamedNodeNotOverAnAndroidViewIsStillMoreSpecificThanTheHost() {
    val chain = root + handler()
    val pick = decide(chain, mapOf(3 to Sem("Node")))
    assertEquals(3, (pick as HitPick.Semantic).hostIndex)
  }

  @Test
  fun withNothingInsideTheHostIsTheAnswerNeverTheHandler() {
    assertEquals(HitPick.View(3), decide(root + handler(), emptyMap()))
  }

  @Test
  fun wrappersAreNeverAskedForSemantics() {
    val asked = ArrayList<Int>()
    val chain = root + handler() + holder()
    HitDecision.decide(chain, { asked.add(it); null }) { _: Sem -> true }
    assertEquals(listOf(3, 2, 1, 0), asked)
  }

  @Test
  fun theDeepestHostWinsWhenComposeIsNested() {
    // A ComposeView inside an AndroidView inside Compose.
    val chain = root + handler() + holder() + plain("FrameLayout") + compose()
    val pick = decide(chain, mapOf(3 to Sem("Node", id = "outer"), 7 to Sem("Button", id = "inner"))) as HitPick.Semantic
    assertEquals("inner", pick.hit.id)
    assertEquals(7, pick.hostIndex)
  }

  @Test
  fun aPlainViewScreenIsUnchanged() {
    val chain = listOf(plain("DecorView"), plain("LinearLayout"), plain("MaterialButton", id = "remove_item", label = "Remove"))
    assertEquals(HitPick.View(2), decide(chain, emptyMap()))
  }

  @Test
  fun aCustomTestersHitOnAPlainViewIsKeptEvenUnnamed() {
    val chain = listOf(plain("DecorView"), plain("ChartView"))
    val pick = decide(chain, mapOf(1 to Sem("Bar"))) as HitPick.Semantic
    assertEquals("Bar", pick.hit.name)
  }

  @Test
  fun noChainNoPick() {
    assertNull(decide(emptyList(), emptyMap()))
  }
}
