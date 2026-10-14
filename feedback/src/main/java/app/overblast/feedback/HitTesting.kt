package app.overblast.feedback

import android.graphics.RectF
import android.view.View

/**
 * What a [NativeHitTester] found inside a view: the steps below that view (the view's own path is
 * added in front), the node's type, id and label, and its bounds in window pixels.
 */
public class NativeHit(
  public val path: List<String>,
  public val type: String,
  public val id: String?,
  public val label: String?,
  public val boundsInWindow: RectF,
)

/**
 * Looks inside a view that draws its own content, which the View-tree walk cannot see into. The
 * Compose module's tester is found and installed without setup; others are added with
 * [OverblastFeedback.addHitTester].
 */
public interface NativeHitTester {
  /** The deepest node of [view] at ([xInWindow], [yInWindow]), or null when this tester does not handle [view]. */
  public fun hitTest(view: View, xInWindow: Float, yInWindow: Float): NativeHit?
}
