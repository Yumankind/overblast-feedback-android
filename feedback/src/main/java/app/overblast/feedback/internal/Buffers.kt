package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel

/** A fixed-size, thread-safe ring: the newest [capacity] items, oldest first. */
internal class RingBuffer<T>(private val capacity: Int) {
  private val items = ArrayDeque<T>(capacity)

  @Synchronized
  fun add(item: T) {
    if (capacity <= 0) return
    if (items.size == capacity) items.removeFirst()
    items.addLast(item)
  }

  @Synchronized
  fun snapshot(): List<T> = items.toList()

  @Synchronized
  fun clear() = items.clear()

  @get:Synchronized
  val size: Int get() = items.size
}

/** The app's own log lines, from `OverblastFeedback.log`, into the shared intake (§3.5) as `src: "app"`. */
internal class LogRecorder(val book: LogBook = LogBook(), private val clock: () -> Long = System::currentTimeMillis) {
  fun log(level: LogLevel, message: String, src: String = "app") {
    book.add(clock(), level, message, src)
  }

  fun snapshot(): List<LogEntry> = book.snapshot()

  fun clear() = book.clear()
}

/** The network calls the app told us about: method, URL without its query, status, time. No bodies, no headers. */
internal class NetworkRecorder(capacity: Int = Limits.NETWORK_ENTRIES, private val clock: () -> Long = System::currentTimeMillis) {
  private val ring = RingBuffer<NetworkEntry>(capacity)

  fun record(method: String, url: String, status: Int, ms: Long) {
    val clean = Limits.clip(Redactor.redact(Limits.stripQuery(url.trim())), Limits.NETWORK_URL)
    ring.add(NetworkEntry(clock() - ms.coerceAtLeast(0), method, clean, status, ms))
  }

  fun snapshot(): List<NetworkEntry> = ring.snapshot()
}
