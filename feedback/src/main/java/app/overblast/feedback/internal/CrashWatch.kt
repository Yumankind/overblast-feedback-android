package app.overblast.feedback.internal

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Build
import app.overblast.feedback.FeedbackStrings

// THE UNEXPECTED END, ON ANDROID (contract §3.4). Three pieces:
//
//  - a session marker, open while one of the app's activities is started and closed when the last one
//    stops, counted through ActivityLifecycleCallbacks so the library needs no androidx.lifecycle. A
//    process the system kills in the background (which Android does all the time) has a closed marker
//    and is never reported; one that dies in front of the person does not;
//  - a default uncaught-exception handler that writes the exception and the log to disk, synchronously,
//    and then hands the crash to the handler that was there before, so the app still crashes exactly as
//    it would have (the platform's handler shows the dialog and kills the process);
//  - at the next start, `ApplicationExitInfo` (API 30+) for how the process ended, merged by
//    [CrashDecision], and the offer on the first activity that resumes.

internal class CrashWatch(
  private val app: Application,
  private val files: SessionFiles,
  private val persister: LogPersister?,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** When this process's library started; a session's exits and records are matched against it. */
  val sessionStart: Long = clock()

  @Volatile private var pending: PreviousSession? = null
  @Volatile private var installed = false

  /** At start, on the log thread: moves an unclosed session aside and decides whether it is worth an offer. */
  fun rollOver(withLogs: Boolean) {
    val (marker, handler) = files.rollOver() ?: return
    val exit = if (Build.VERSION.SDK_INT >= 30) lastExit(marker.start) else null
    val crash = CrashDecision.decide(marker, handler, exit)
    if (crash == null) {
      files.discardPrevious()
      return
    }
    pending = PreviousSession(crash, if (withLogs) files.previousLogs() else emptyList())
  }

  /** The previous session's end, once: the caller offers it, and nobody is offered it again. */
  fun take(): PreviousSession? = synchronized(this) { pending.also { pending = null } }

  val hasPending: Boolean get() = pending != null

  fun discard() {
    pending = null
    files.discardPrevious()
  }

  fun onForeground() = files.open(sessionStart)

  fun onBackground() {
    persister?.flushNow()
    files.close(sessionStart)
  }

  fun installHandler() {
    if (installed) return
    installed = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try {
        files.writeHandler(CrashDecision.record(error, clock()))
        persister?.flushNow()
      } catch (t: Throwable) {
        // Recording must never be the reason the original crash is lost.
      }
      if (previous != null) {
        previous.uncaughtException(thread, error)
      } else {
        // No handler before ours (the platform always installs one, so this is a test or an odd host):
        // end the process as the platform's default would.
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
      }
    }
  }

  private fun lastExit(sessionStart: Long): ExitRecord? {
    if (Build.VERSION.SDK_INT < 30) return null
    return try {
      val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
      val processName = app.applicationInfo.processName ?: app.packageName
      val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
        .filter { it.processName == processName }
        .map { ExitRecord(it.timestamp, it.reason, it.description) }
      CrashDecision.pickExit(exits, sessionStart)
    } catch (e: Exception) {
      null
    }
  }

  companion object {
    /** The small question, on [activity]. [send] opens the report; anything else declines it. */
    fun offer(activity: Activity, strings: FeedbackStrings, send: () -> Unit, decline: () -> Unit) {
      var answered = false
      val dialog = AlertDialog.Builder(activity)
        .setMessage(strings.crashOffer)
        .setPositiveButton(strings.crashOfferSend) { _, _ ->
          answered = true
          send()
        }
        .setNegativeButton(strings.crashOfferDecline) { _, _ ->
          answered = true
          decline()
        }
        .create()
      dialog.setOnDismissListener { if (!answered) decline() }
      dialog.show()
    }
  }
}
