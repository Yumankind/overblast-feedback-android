package app.overblast.feedback.compose

import android.graphics.RectF
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.overblast.feedback.NativeHit
import app.overblast.feedback.NativeHitTester

/**
 * Tap-to-select inside Compose: the deepest semantics node under the tap that has a test tag, a
 * content description or text (else the deepest node at all), from the unmerged semantics
 * tree of the `AndroidComposeView` the View walk landed on. `OverblastFeedback` installs it by itself
 * when this module is on the classpath.
 *
 * The node's `id` is its `testTag`; its `label` is its content description, else its text (never the
 * text of an editable or password field); its `type` is its Role (`Button`, `Checkbox`, ...), `Text`
 * for a text node, or `Node`.
 */
public object ComposeHitTester : NativeHitTester {
  private const val MAX_DEPTH = 64

  override fun hitTest(view: View, xInWindow: Float, yInWindow: Float): NativeHit? {
    val root = (view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
    val steps = ArrayList<String>()
    var node: SemanticsNode = root
    var found: SemanticsNode? = null
    var foundSteps = 0
    var said: SemanticsNode? = null
    var saidSteps = 0
    var depth = 0
    while (depth++ < MAX_DEPTH) {
      // Later children are drawn on top.
      val next = node.children.lastOrNull { child ->
        val b = child.boundsInWindow
        child.layoutInfo.isPlaced && xInWindow >= b.left && xInWindow < b.right && yInWindow >= b.top && yInWindow < b.bottom
      } ?: break
      steps.add(typeOf(next))
      found = next
      foundSteps = steps.size
      if (says(next)) {
        said = next
        saidSteps = steps.size
      }
      node = next
    }
    // The deepest node that says something (a test tag, a description, text) rather than the deepest
    // node: below a tagged card there is usually only anonymous layout, and the tag is what names it.
    val hit = said ?: found ?: return null
    val taken = steps.take(if (said != null) saidSteps else foundSteps)
    // Keep the path to the nodes that say something: a deep Compose tree is mostly anonymous layout.
    val path = taken.filter { it != "Node" }.ifEmpty { listOf(typeOf(hit)) }
    val b = hit.boundsInWindow
    return NativeHit(
      path = path,
      type = typeOf(hit),
      id = hit.config.getOrNull(SemanticsProperties.TestTag),
      label = labelOf(hit),
      boundsInWindow = RectF(b.left, b.top, b.right, b.bottom),
    )
  }

  private fun says(n: SemanticsNode): Boolean =
    n.config.getOrNull(SemanticsProperties.TestTag) != null || labelOf(n) != null

  private fun typeOf(n: SemanticsNode): String {
    val c = n.config
    c.getOrNull(SemanticsProperties.Role)?.let { return roleName(it) }
    if (c.getOrNull(SemanticsProperties.EditableText) != null) return "TextField"
    if (c.getOrNull(SemanticsProperties.Text) != null) return "Text"
    c.getOrNull(SemanticsProperties.TestTag)?.let { return it }
    return "Node"
  }

  private fun roleName(r: Role): String = when (r) {
    Role.Button -> "Button"
    Role.Checkbox -> "Checkbox"
    Role.Switch -> "Switch"
    Role.RadioButton -> "RadioButton"
    Role.Tab -> "Tab"
    Role.Image -> "Image"
    Role.DropdownList -> "DropdownList"
    else -> "Node"
  }

  private fun labelOf(n: SemanticsNode): String? {
    val c = n.config
    c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (c.getOrNull(SemanticsProperties.Password) != null || c.getOrNull(SemanticsProperties.EditableText) != null) return null
    return c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim()?.takeIf { it.isNotEmpty() }
  }
}
