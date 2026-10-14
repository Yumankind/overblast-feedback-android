package app.overblast.feedback.internal

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// THE `context` OBJECT (contract §3.1), read on the main thread when the sheet opens, so the screen
// and title are the ones the reporter was looking at rather than whatever is on screen at Send.
internal object DeviceInfo {
  private const val PREFS = "app.overblast.feedback"
  private const val DEVICE_ID = "device_id"

  @Volatile private var cachedDeviceId: String? = null

  /** A random id kept per install, so the worker's per-device limit counts this phone and not its network. */
  fun deviceId(context: Context): String {
    cachedDeviceId?.let { return it }
    return synchronized(this) { cachedDeviceId ?: readOrCreate(context).also { cachedDeviceId = it } }
  }

  private fun readOrCreate(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(DEVICE_ID, null)?.let { return it }
    val id = UUID.randomUUID().toString()
    prefs.edit().putString(DEVICE_ID, id).apply()
    return id
  }

  fun context(
    activity: Activity?,
    app: Context,
    screenName: ((Activity) -> String?)?,
    extra: Map<String, String>,
    sdkVersion: String,
  ): FeedbackContext {
    val pkg = packageInfo(app)
    val metrics = (activity ?: app).resources.displayMetrics
    val decor = activity?.window?.decorView
    val wPx = decor?.width?.takeIf { it > 0 } ?: metrics.widthPixels
    val hPx = decor?.height?.takeIf { it > 0 } ?: metrics.heightPixels
    val density = if (metrics.density > 0f) metrics.density else 1f
    val screen = activity?.let { a -> runCatching { screenName?.invoke(a) }.getOrNull() ?: ViewHitTester.simpleNameOf(a.javaClass) }
    return FeedbackContext(
      screen = screen,
      title = activity?.title?.toString(),
      appVersion = pkg?.versionName,
      build = pkg?.let { versionCode(it).toString() },
      os = "Android",
      osVersion = Build.VERSION.RELEASE,
      device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").let { deduplicate(it) },
      locale = Locale.getDefault().toLanguageTag(),
      timeZone = TimeZone.getDefault().id,
      viewport = ContextViewport(Math.round(wPx / density), Math.round(hPx / density), Math.round(density * 100) / 100.0),
      sdkVersion = sdkVersion,
      deviceId = deviceId(app),
      extra = extra,
    )
  }

  /** "Google Google Pixel 8" reads badly; when the model already starts with the maker, keep the model. */
  private fun deduplicate(s: String): String {
    val parts = s.split(" ", limit = 2)
    if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) return parts[1]
    return s
  }

  @Suppress("DEPRECATION")
  private fun packageInfo(app: Context): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= 33) {
      app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      app.packageManager.getPackageInfo(app.packageName, 0)
    }
  } catch (e: Exception) {
    null
  }

  @Suppress("DEPRECATION")
  private fun versionCode(p: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
}
