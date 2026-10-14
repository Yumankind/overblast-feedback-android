package app.overblast.feedback.internal

import kotlin.math.sqrt

// WHEN A SHAKE IS A SHAKE. Pure arithmetic over accelerometer samples, so it is tested on the JVM:
// two spikes over the threshold within [windowMs] make a shake, and after one, nothing more counts for
// [debounceMs], so one vigorous shake opens the sheet once.
internal class ShakeLogic(
  private val thresholdG: Float,
  private val windowMs: Long = 600,
  private val debounceMs: Long = 2000,
  private val spikesNeeded: Int = 2,
) {
  private var spikes = 0
  private var lastSpikeAt = Long.MIN_VALUE / 2
  private var lastShakeAt = Long.MIN_VALUE / 2

  /** One sample in m/s². True when this sample completes a shake. */
  fun onSample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
    val g = sqrt(x * x + y * y + z * z) / GRAVITY
    if (g < thresholdG) return false
    if (nowMs - lastShakeAt < debounceMs) return false
    if (nowMs - lastSpikeAt > windowMs) spikes = 0
    spikes++
    lastSpikeAt = nowMs
    if (spikes >= spikesNeeded) {
      spikes = 0
      lastShakeAt = nowMs
      return true
    }
    return false
  }

  companion object {
    const val GRAVITY = 9.80665f
  }
}
