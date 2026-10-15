package app.overblast.feedback.internal

// WHICH ELEMENT A TAP SELECTS, as a decision over the chain of views under the finger (root first) and
// what each view's semantics say. Pure, so the order is pinned by JVM tests with fake hits.
//
// A Compose screen that hosts an AndroidView (a map, a camera preview) has, under AndroidComposeView, a
// full-screen AndroidViewsHandler that holds every AndroidView. The View walk ends inside it wherever
// the finger lands, so a View-first order selects that handler (the whole screen) instead of the Compose
// button that was tapped. The order is therefore:
//
//  1. the deepest view with semantics of its own (a Compose host through the `:compose` tester or the
//     accessibility fallback, or a view a custom tester handles), when its node under the finger says
//     something: a test tag or resource id, a content description, or text;
//  2. below a Compose host, the hosted AndroidView itself: the deepest view under the wrappers that says
//     something, else the wrappers' first child (the MapView, not its inner TextureView);
//  3. the semantics node even when it says nothing, because it is still smaller than its host;
//  4. the Compose host, or, with no Compose at all, the deepest view, as before.
//
// AndroidViewsHandler and the holder around each AndroidView are never the answer while anything more
// specific was hit.

internal enum class ViewKind { PLAIN, COMPOSE_HOST, WRAPPER }

internal class ViewStep(val name: String, val kind: ViewKind, val id: String?, val label: String?) {
  val meaningful: Boolean get() = id != null || label != null
}

internal sealed class HitPick<out T> {
  /** The semantics node [hit], found inside the view at [hostIndex]. */
  data class Semantic<T>(val hostIndex: Int, val hit: T) : HitPick<T>()

  /** The view at [index] in the chain. */
  data class View(val index: Int) : HitPick<Nothing>()
}

internal object HitDecision {
  fun <T> decide(chain: List<ViewStep>, semanticsAt: (Int) -> T?, meaningful: (T) -> Boolean): HitPick<T>? {
    if (chain.isEmpty()) return null
    // The deepest view that has semantics under the finger.
    var hostIndex = -1
    var hit: T? = null
    for (i in chain.indices.reversed()) {
      if (chain[i].kind == ViewKind.WRAPPER) continue
      val s = semanticsAt(i) ?: continue
      hostIndex = i
      hit = s
      break
    }
    if (hit != null && meaningful(hit)) return HitPick.Semantic(hostIndex, hit)

    // Below the deepest Compose host: the AndroidView it hosts, if the finger is on one.
    val composeIndex = chain.indices.lastOrNull { chain[it].kind == ViewKind.COMPOSE_HOST }
    if (composeIndex != null) {
      val hosted = ((composeIndex + 1) until chain.size).filter { chain[it].kind != ViewKind.WRAPPER }
      if (hosted.isNotEmpty()) {
        val said = hosted.lastOrNull { chain[it].meaningful }
        return HitPick.View(said ?: hosted.first())
      }
    }
    if (hit != null) return HitPick.Semantic(hostIndex, hit)
    if (composeIndex != null) return HitPick.View(composeIndex)
    // No Compose: the deepest view that is not a wrapper (which, without Compose, is the deepest view).
    return HitPick.View(chain.indices.last { chain[it].kind != ViewKind.WRAPPER || it == 0 })
  }
}
