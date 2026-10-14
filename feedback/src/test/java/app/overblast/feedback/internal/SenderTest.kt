package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SenderTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private class FakePoster(vararg answers: Any) : HttpPoster {
    val queue = ArrayDeque(answers.toList())
    val bodies = ArrayList<String>()
    var headers: Map<String, String> = emptyMap()

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpAnswer {
      this.headers = headers
      bodies.add(String(body, Charsets.UTF_8))
      return when (val a = queue.removeFirst()) {
        is HttpAnswer -> a
        is IOException -> throw a
        else -> error("bad fake")
      }
    }
  }

  private fun store() = PendingStore(DirectoryPendingFiles(tmp.newFolder("pending")))

  @Test
  fun sendsTheKeyHeader() {
    val poster = FakePoster(HttpAnswer(201, """{"id":"fbr_1","taskId":"t","conversationId":"c"}"""))
    val r = FeedbackSender("https://x.test/v1/feedback", "obfk_abc", poster, store()).send("{}")
    assertEquals(FeedbackResult.Sent("fbr_1", "t", "c"), r)
    assertEquals("obfk_abc", poster.headers["X-Overblast-Feedback-Key"])
    assertTrue(poster.headers["Content-Type"]!!.startsWith("application/json"))
  }

  @Test
  fun rateLimitedIsSavedOnceAndNotRetriedInALoop() {
    val pending = store()
    val poster = FakePoster(HttpAnswer(429, """{"code":"rate_limited","error":"Slow down.","retryAfter":60}"""))
    val r = FeedbackSender("u", "k", poster, pending).send("""{"v":1}""")
    assertEquals(FeedbackResult.SavedForLater("Slow down.", 60), r)
    assertEquals(1, poster.bodies.size)
    assertEquals(1, pending.count)
  }

  @Test
  fun networkFailureIsNotSaved() {
    val pending = store()
    val r = FeedbackSender("u", "k", FakePoster(IOException("offline")), pending).send("{}")
    assertEquals("network", (r as FeedbackResult.Failed).code)
    assertEquals(0, r.status)
    assertEquals(0, pending.count)
  }

  @Test
  fun retryPendingSendsOldestFirstAndStopsAtTheNextRefusal() {
    var now = 0L
    val pending = PendingStore(DirectoryPendingFiles(tmp.newFolder("p2")), clock = { now++ })
    pending.save("one")
    pending.save("two")
    pending.save("three")
    pending.save("four")
    val poster = FakePoster(
      HttpAnswer(201, "{}"),
      HttpAnswer(400, """{"code":"invalid_field","field":"message","error":"x"}"""),
      HttpAnswer(429, "{}"),
    )
    val s = FeedbackSender("u", "k", poster, pending).retryPending()
    assertEquals(RetrySummary(sent = 1, dropped = 1, kept = 2), s)
    assertEquals(listOf("one", "two", "three"), poster.bodies)
    assertEquals(2, pending.count)
    assertEquals("three", pending.read(pending.names().first()))
  }

  @Test
  fun pendingStoreIsBounded() {
    var now = 0L
    val pending = PendingStore(DirectoryPendingFiles(tmp.newFolder("p3")), maxItems = 3, clock = { now++ })
    (1..5).forEach { pending.save("r$it") }
    assertEquals(listOf("r3", "r4", "r5"), pending.names().map { pending.read(it) })
  }

  @Test
  fun pendingStoreWorksOverAnyFiles() {
    val map = LinkedHashMap<String, String>()
    val files = object : PendingFiles {
      override fun list() = map.keys.toList()
      override fun read(name: String) = map[name]
      override fun write(name: String, content: String) { map[name] = content }
      override fun delete(name: String) { map.remove(name) }
    }
    val pending = PendingStore(files)
    assertTrue(pending.save("x"))
    assertEquals(1, map.size)
    assertTrue(map.keys.single().endsWith(".json"))
  }
}
