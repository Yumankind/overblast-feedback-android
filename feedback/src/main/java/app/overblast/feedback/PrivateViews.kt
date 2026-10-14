package app.overblast.feedback

import android.graphics.RectF
import android.view.View
import java.lang.ref.WeakReference

/**
 * Marks this view as private: it is painted over with a solid box in every screenshot the library
 * takes, and its text is never used as the selected element's label. Password fields and card-number
 * fields are treated this way without being marked.
 *
 * ```kotlin
 * binding.balance.overblastPrivate()
 * ```
 */
public fun <T : View> T.overblastPrivate(private: Boolean = true): T {
  setTag(R.id.overblast_feedback_private, if (private) true else null)
  return this
}

/** True when [overblastPrivate] was called on this view. */
public val View.isOverblastPrivate: Boolean
  get() = getTag(R.id.overblast_feedback_private) == true

/**
 * Private areas that are not views of their own: the bounds the Compose module's
 * `Modifier.overblastPrivate()` reports, in window pixels, against the view that hosts them. Public so
 * that module (and any other UI toolkit drawing into one view) can register; an app does not call it.
 */
public object OverblastPrivateRegions {
  private class Region(val host: WeakReference<View>, val bounds: RectF)

  private val regions = LinkedHashMap<Any, Region>()

  /** Records or moves the private area [owner] draws, [boundsInWindow] in [host]'s window. */
  @JvmStatic
  public fun put(owner: Any, host: View, boundsInWindow: RectF) {
    synchronized(regions) { regions[owner] = Region(WeakReference(host), RectF(boundsInWindow)) }
  }

  /** Forgets [owner]'s area, when it leaves the screen. */
  @JvmStatic
  public fun remove(owner: Any) {
    synchronized(regions) { regions.remove(owner) }
  }

  /** The areas whose host is attached under [root], in window pixels. */
  internal fun boundsUnder(root: View): List<RectF> = synchronized(regions) {
    val dead = ArrayList<Any>()
    val out = ArrayList<RectF>()
    for ((k, r) in regions) {
      val host = r.host.get()
      if (host == null) {
        dead.add(k)
        continue
      }
      if (host.isAttachedToWindow && host.rootView === root && host.isShown) out.add(RectF(r.bounds))
    }
    dead.forEach { regions.remove(it) }
    out
  }
}
