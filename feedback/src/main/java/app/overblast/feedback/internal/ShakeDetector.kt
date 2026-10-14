package app.overblast.feedback.internal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/** The accelerometer, listened to only between [start] and [stop]: the library calls those on resume and pause. */
internal class ShakeDetector(thresholdG: Float, private val onShake: () -> Unit) : SensorEventListener {
  private val logic = ShakeLogic(thresholdG)
  private var manager: SensorManager? = null

  fun start(context: Context) {
    if (manager != null) return
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
    val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    if (sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)) manager = sm
  }

  fun stop() {
    manager?.unregisterListener(this)
    manager = null
  }

  override fun onSensorChanged(event: SensorEvent) {
    val v = event.values
    if (v.size < 3) return
    if (logic.onSample(v[0], v[1], v[2], SystemClock.elapsedRealtime())) onShake()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
