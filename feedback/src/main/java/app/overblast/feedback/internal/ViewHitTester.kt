package app.overblast.feedback.internal

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText
import android.widget.TextView
import app.overblast.feedback.NativeHit
import app.overblast.feedback.NativeHitTester

// TAP-TO-SELECT. The reporter taps the frozen screenshot; the point is hit-tested against the LIVE view
// hierarchy under the sheet (the screen has not moved: the sheet is a dialog over it). The deepest
// visible view containing the point wins, and its path is the simple class names from the decor view.
//
// A Compose screen is one view (AndroidComposeView) that draws everything itself, so the walk ends
// there. Inside it, in order:
//   1. a registered NativeHitTester, which is what the `:compose` module installs: it walks the
//      semantics tree, the reliable way;
//   2. otherwise the view's AccessibilityNodeProvider, best effort. A provider hands out the root
//      node, but the public API only lists a node's children through an accessibility-service
//      connection this process does not have, so the children are reached through the hidden
//      `getChildId`. Where the platform refuses that, the selection is the Compose view itself.
internal class HitResult(val path: List<String>, val type: String, val id: String?, val label: String?, val boundsInWindow: RectF)

internal object ViewHitTester {
  private const val MAX_NODES = 3000

  fun hitTest(root: View, x: Float, y: Float, testers: List<NativeHitTester>): HitResult? {
    val chain = ArrayList<View>()
    if (!deepest(root, x, y, chain)) return null
    val view = chain.last()
    val path = chain.map { simpleName(it) }
    for (t in testers) {
      val hit = runCatching { t.hitTest(view, x, y) }.getOrNull() ?: continue
      return HitResult(path + hit.path, hit.type, hit.id, hit.label, RectF(hit.boundsInWindow))
    }
    if (view.accessibilityNodeProvider != null) {
      val hit = runCatching { accessibilityHit(view, x, y) }.getOrNull()
      if (hit != null) return HitResult(path + hit.path, hit.type, hit.id, hit.label, hit.boundsInWindow)
    }
    return HitResult(path, simpleName(view), resourceName(view), label(view), boundsInWindow(view))
  }

  /** Fills [chain] with the views from [view] down to the deepest one at (x, y). */
  private fun deepest(view: View, x: Float, y: Float, chain: MutableList<View>): Boolean {
    if (view.visibility != View.VISIBLE || view.alpha <= 0f) return false
    val b = boundsInWindow(view)
    if (!b.contains(x, y)) return false
    chain.add(view)
    if (view is ViewGroup) {
      // Last child is drawn last, so it is on top.
      for (i in view.childCount - 1 downTo 0) {
        if (deepest(view.getChildAt(i), x, y, chain)) return true
      }
    }
    return true
  }

  fun boundsInWindow(view: View): RectF {
    val loc = IntArray(2)
    view.getLocationInWindow(loc)
    return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
  }

  fun simpleName(view: View): String = simpleNameOf(view.javaClass)

  fun simpleNameOf(c: Class<*>): String {
    var cls: Class<*>? = c
    while (cls != null) {
      val n = cls.simpleName
      if (n.isNotEmpty()) return n
      cls = cls.superclass // anonymous subclasses have no simple name
    }
    return "View"
  }

  /** The `android:id` entry name, when the view has one from resources (generated ids have none). */
  fun resourceName(view: View): String? {
    val id = view.id
    if (id == View.NO_ID || (id ushr 24) == 0) return null
    return try {
      view.resources.getResourceEntryName(id)
    } catch (e: Exception) {
      null
    }
  }

  /** contentDescription, else visible text. Never the text of an input or a private view, which is the reporter's data. */
  fun label(view: View): String? {
    view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (ScreenCapture.isPrivate(view)) return null
    if (view is EditText) return view.hint?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (view is TextView) return view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    return null
  }

  // ── The accessibility fallback ──────────────────────────────────────────────────────────────

  private val getChildId by lazy {
    runCatching { AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).also { it.isAccessible = true } }.getOrNull()
  }

  private fun accessibilityHit(host: View, x: Float, y: Float): NativeHit? {
    val provider = host.accessibilityNodeProvider ?: return null
    val root = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID) ?: return null
    val origin = IntArray(2)
    host.rootView.getLocationOnScreen(origin)
    val steps = ArrayList<String>()
    var best: AccessibilityNodeInfo? = null
    var node: AccessibilityNodeInfo? = root
    var visited = 0
    val method = getChildId ?: return null
    while (node != null && visited < MAX_NODES) {
      var next: AccessibilityNodeInfo? = null
      // Last child is on top, as with views.
      for (i in node.childCount - 1 downTo 0) {
        visited++
        val childId = (method.invoke(node, i) as? Long) ?: continue
        val virtualId = (childId ushr 32).toInt()
        val child = provider.createAccessibilityNodeInfo(virtualId) ?: continue
        if (!child.isVisibleToUser) continue
        val r = Rect()
        child.getBoundsInScreen(r)
        r.offset(-origin[0], -origin[1])
        if (r.contains(x.toInt(), y.toInt())) {
          next = child
          break
        }
      }
      if (next == null) break
      steps.add(nodeType(next))
      best = next
      node = next
    }
    val found = best ?: return null
    val r = Rect()
    found.getBoundsInScreen(r)
    r.offset(-origin[0], -origin[1])
    val label = found.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
      ?: if (found.isPassword || found.isEditable) null else found.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    return NativeHit(
      path = steps,
      type = nodeType(found),
      id = found.viewIdResourceName,
      label = label,
      boundsInWindow = RectF(r),
    )
  }

  private fun nodeType(n: AccessibilityNodeInfo): String {
    val cls = n.className?.toString().orEmpty()
    return cls.substringAfterLast('.').ifEmpty { "Node" }
  }
}
