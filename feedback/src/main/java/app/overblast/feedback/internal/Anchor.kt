package app.overblast.feedback.internal

// THE `native` ELEMENT (contract §3.2), from what a hit test found: the path of type names from the
// root, the node's own type, id and label, and its bounds in window pixels. The bounds become fractions
// of the window, and the viewport is the window in dp, the same unit `context.viewport` uses.
internal object AnchorBuilder {
  fun build(
    path: List<String>,
    type: String,
    id: String?,
    label: String?,
    leftPx: Float,
    topPx: Float,
    widthPx: Float,
    heightPx: Float,
    windowWidthPx: Int,
    windowHeightPx: Int,
    density: Float,
  ): NativeElement {
    val ww = windowWidthPx.coerceAtLeast(1).toDouble()
    val wh = windowHeightPx.coerceAtLeast(1).toDouble()
    // Clip the box to the window first, so a view half off-screen gets the visible half.
    val l = leftPx.toDouble().coerceIn(0.0, ww)
    val t = topPx.toDouble().coerceIn(0.0, wh)
    val r = (leftPx + widthPx).toDouble().coerceIn(0.0, ww)
    val b = (topPx + heightPx).toDouble().coerceIn(0.0, wh)
    val d = if (density > 0f) density.toDouble() else 1.0
    val steps = path.mapNotNull { Limits.opt(it, Limits.PATH_STEP) }
    return NativeElement(
      path = if (steps.size > Limits.PATH_STEPS) steps.takeLast(Limits.PATH_STEPS) else steps,
      type = Limits.opt(type, Limits.ELEMENT_TYPE) ?: "View",
      id = Limits.opt(id, Limits.ELEMENT_ID),
      label = Limits.opt(label?.replace(Regex("\\s+"), " "), Limits.ELEMENT_LABEL),
      rect = FractionRect(
        x = Limits.fraction(l / ww),
        y = Limits.fraction(t / wh),
        w = Limits.fraction((r - l) / ww),
        h = Limits.fraction((b - t) / wh),
      ),
      viewport = ElementViewport(
        w = Math.round(ww / d).toInt().coerceAtLeast(1),
        h = Math.round(wh / d).toInt().coerceAtLeast(1),
      ),
    )
  }
}
