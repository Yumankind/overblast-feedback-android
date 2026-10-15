package app.overblast.feedback.internal

import app.overblast.feedback.DeviceLevel
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What the sender needs from device verification. An interface so the sender's tests can fake it whole. */
internal interface DeviceSigner {
  /**
   * The registered device. With none, joins the registration under way or starts one (unless one
   * failed moments ago), waiting up to [waitMs]; null when that did not give a device in time.
   */
  fun ready(waitMs: Long): DeviceRecord?

  /** The signature header's bytes for [data], or null when the key is gone (the record is then forgotten). */
  fun sign(record: DeviceRecord, data: ByteArray): ByteArray?

  /** Drops [record] and its key, so the next report registers again. */
  fun forget(record: DeviceRecord)
}

/**
 * THE DEVICE, ACROSS A PROCESS. Registration is lazy: nothing happens at start; [prepare] (called when
 * the sheet opens) starts it on its own thread, so it is usually done by the time Send is pressed, and
 * Send waits at most three seconds more before sending unsigned. A device the worker registered is kept
 * as it is, whatever its level: the contract asks a library not to re-register for a better one.
 *
 * A registration that fails leaves the doors alone for a while (the worker's `retryAfter`, or a minute),
 * so a phone without a network does not spend its address's challenge allowance one sheet at a time.
 */
internal class DeviceSession(
  private val registrar: DeviceRegistrar,
  private val keys: DeviceKeys,
  private val store: DeviceStore,
  private val alias: String,
  private val executor: Executor,
  private val clock: () -> Long = System::currentTimeMillis,
  private val log: (String) -> Unit = {},
) : DeviceSigner {
  private val lock = Any()
  @Volatile private var loaded = false
  @Volatile private var record: DeviceRecord? = null
  private var inFlight: FutureTask<DeviceRecord?>? = null
  private var quietUntil = 0L
  @Volatile private var lastFailed = false

  /** The level this device has, `NONE` after a failed registration, null before anything is known. */
  val level: DeviceLevel?
    get() {
      load()
      return record?.level ?: if (lastFailed) DeviceLevel.NONE else null
    }

  /** Starts a registration in the background when there is no device and none is under way. Never blocks on the network. */
  fun prepare() {
    // On the registration thread, so the first read of the stored record is never on the main thread.
    try {
      executor.execute { start() }
    } catch (e: RuntimeException) {
      // A rejected task only means no head start; Send starts the registration itself.
    }
  }

  override fun ready(waitMs: Long): DeviceRecord? {
    load()
    record?.let { return it }
    val task = start() ?: return record
    return await(task, waitMs)
  }

  override fun sign(record: DeviceRecord, data: ByteArray): ByteArray? {
    val sig = try {
      keys.sign(record.alias, data)
    } catch (e: Exception) {
      null
    }
    if (sig == null) {
      // The key is gone while the record stayed: an Auto Backup restore carries SharedPreferences to a
      // new phone, never a keystore key. Forget it so the next report registers this phone.
      log("the device key is missing; registering again")
      forget(record)
    }
    return sig
  }

  override fun forget(record: DeviceRecord) {
    synchronized(lock) {
      if (this.record?.deviceId == record.deviceId) this.record = null
      quietUntil = 0
      lastFailed = false
    }
    runCatching { store.clear(record.alias) }
    runCatching { keys.delete(record.alias) }
  }

  private fun load() {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      record = runCatching { store.load(alias) }.getOrNull()
      loaded = true
    }
  }

  private fun start(): FutureTask<DeviceRecord?>? {
    load()
    synchronized(lock) {
      if (record != null) return null
      inFlight?.let { if (!it.isDone) return it }
      if (clock() < quietUntil) return null
      val task = FutureTask { runRegistration() }
      inFlight = task
      try {
        executor.execute(task)
      } catch (e: RuntimeException) {
        inFlight = null
        return null
      }
      return task
    }
  }

  private fun runRegistration(): DeviceRecord? {
    val outcome = try {
      registrar.register()
    } catch (e: Exception) {
      RegistrationOutcome.Failed("error_${e.javaClass.simpleName}")
    }
    return when (outcome) {
      is RegistrationOutcome.Registered -> {
        runCatching { store.save(outcome.record) }
        synchronized(lock) {
          record = outcome.record
          lastFailed = false
        }
        log("device registered: ${outcome.record.method}, ${outcome.record.level.wire}${outcome.reason?.let { " ($it)" } ?: ""}")
        outcome.record
      }
      is RegistrationOutcome.Failed -> {
        synchronized(lock) {
          lastFailed = true
          val seconds = (outcome.retryAfterSeconds ?: DEFAULT_QUIET_SECONDS).coerceIn(1, MAX_QUIET_SECONDS)
          quietUntil = clock() + seconds * 1000
        }
        log("device registration failed: ${outcome.code}; reports go unsigned")
        null
      }
    }
  }

  private fun await(task: FutureTask<DeviceRecord?>, waitMs: Long): DeviceRecord? = try {
    if (waitMs <= 0) {
      if (task.isDone) task.get() else null
    } else {
      task.get(waitMs, TimeUnit.MILLISECONDS)
    }
  } catch (e: TimeoutException) {
    null
  } catch (e: ExecutionException) {
    null
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    null
  } catch (e: java.util.concurrent.CancellationException) {
    null
  }

  companion object {
    const val DEFAULT_QUIET_SECONDS = 60L
    const val MAX_QUIET_SECONDS = 3600L
  }
}
