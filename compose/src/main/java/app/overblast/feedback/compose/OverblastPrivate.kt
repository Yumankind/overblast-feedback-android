package app.overblast.feedback.compose

import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import app.overblast.feedback.OverblastPrivateRegions

/**
 * Marks this composable as private: its bounds are painted over with a solid box in every screenshot
 * the library takes. The Compose counterpart of `View.overblastPrivate()`.
 *
 * ```kotlin
 * Text(balance, Modifier.overblastPrivate())
 * ```
 */
public fun Modifier.overblastPrivate(): Modifier = this then OverblastPrivateElement

private data object OverblastPrivateElement : ModifierNodeElement<OverblastPrivateNode>() {
  override fun create(): OverblastPrivateNode = OverblastPrivateNode()

  override fun update(node: OverblastPrivateNode) = Unit

  override fun InspectorInfo.inspectableProperties() {
    name = "overblastPrivate"
  }
}

/** Reports its bounds in window pixels on every placement, and forgets them when it leaves the composition. */
private class OverblastPrivateNode : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
  override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
    if (!coordinates.isAttached) return
    val b = coordinates.boundsInWindow()
    OverblastPrivateRegions.put(this, currentValueOf(LocalView), RectF(b.left, b.top, b.right, b.bottom))
  }

  override fun onDetach() {
    OverblastPrivateRegions.remove(this)
  }
}
