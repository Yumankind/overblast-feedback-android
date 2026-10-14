package app.overblast.feedback.internal

import java.io.File

// REPORTS KEPT FOR LATER (contract §4): a report refused with 429 is written to the app's own files
// directory and sent again by `retryPending()` or on the next start, once each time, never in a loop.
// The store is bounded, because a report with a screenshot is several megabytes: past [maxItems] the
// oldest is dropped.

/** The four file operations the store needs, so a test can hand it a temporary directory or a map. */
internal interface PendingFiles {
  fun list(): List<String>
  fun read(name: String): String?
  fun write(name: String, content: String)
  fun delete(name: String)
}

internal class DirectoryPendingFiles(private val dir: File) : PendingFiles {
  override fun list(): List<String> =
    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.map { it.name }.orEmpty()

  override fun read(name: String): String? = runCatching { File(dir, name).readText(Charsets.UTF_8) }.getOrNull()

  override fun write(name: String, content: String) {
    if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("cannot create ${dir.path}")
    // Written beside and renamed into place, so a crash mid-write never leaves half a report to resend.
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!tmp.renameTo(File(dir, name))) {
      tmp.delete()
      throw java.io.IOException("cannot rename into ${dir.path}")
    }
  }

  override fun delete(name: String) {
    File(dir, name).delete()
  }
}

internal class PendingStore(
  private val files: PendingFiles,
  private val maxItems: Int = 10,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private var counter = 0

  /** Oldest first: names start with a zero-padded timestamp, so they sort in the order they were saved. */
  @Synchronized
  fun names(): List<String> = files.list().sorted()

  @Synchronized
  fun read(name: String): String? = files.read(name)

  @Synchronized
  fun delete(name: String) = files.delete(name)

  /** Keeps [body]; false when it could not be written. */
  @Synchronized
  fun save(body: String): Boolean {
    val name = "%015d-%04d.json".format(clock(), (counter++) % 10_000)
    return try {
      files.write(name, body)
      val all = names()
      if (all.size > maxItems) all.take(all.size - maxItems).forEach { files.delete(it) }
      true
    } catch (e: Exception) {
      false
    }
  }

  val count: Int get() = names().size
}
