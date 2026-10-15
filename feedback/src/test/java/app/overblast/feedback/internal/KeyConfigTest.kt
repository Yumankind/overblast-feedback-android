package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KeyConfigTest {
  private class MemStore(var kept: StoredConfig? = null) : ConfigStore {
    override fun load() = kept
    override fun save(c: StoredConfig) {
      kept = c
    }
  }

  private class FakeGetter(vararg answers: Any) : HttpGetter {
    val queue = ArrayDeque(answers.toList())
    val requests = ArrayList<Map<String, String>>()
    override fun get(url: String, headers: Map<String, String>): HttpAnswer {
      requests.add(headers)
      return when (val a = queue.removeFirst()) {
        is HttpAnswer -> a
        is IOException -> throw a
        else -> error("bad")
      }
    }
  }

  private val flag = """{"verification":"flag","errorPrompt":{"enabled":true,"on":["exception","server_error"],"minHoursBetween":6},"screenshots":false}"""

  @Test
  fun beforeAnyAnswerTheDefaultsApply() {
    val c = KeyConfigClient("https://x/v1/feedback", "k", FakeGetter(), MemStore(), "ua").config
    assertEquals(KeyConfig(), c)
    assertFalse(c.registersDevices)
    assertFalse(c.errorPrompt.enabled)
    assertTrue(c.screenshots)
  }

  @Test
  fun parsesTheAnswerAndToleratesWhatItDoesNotKnow() {
    val c = KeyConfig.parse(flag)!!
    assertTrue(c.registersDevices)
    assertEquals(setOf("exception", "server_error"), c.errorPrompt.on)
    assertEquals(6.0, c.errorPrompt.minHoursBetween, 0.0)
    assertFalse(c.screenshots)
    assertTrue(KeyConfig.parse("""{"verification":"require","future":1}""")!!.registersDevices)
    assertFalse(KeyConfig.parse("""{"verification":"maybe"}""")!!.registersDevices)
    assertNull(KeyConfig.parse("<html>"))
  }

  @Test
  fun readsTheDoorKeepsTheAnswerAndSendsTheEtagNextTime() {
    var now = 1_000_000L
    val store = MemStore()
    val getter = FakeGetter(HttpAnswer(200, flag, mapOf("etag" to "\"v1\"")), HttpAnswer(304, null))
    val client = KeyConfigClient("https://x/v1/feedback/", "obfk_k", getter, store, "ua", { now })
    assertEquals("https://x/v1/feedback/config", client.url)
    assertTrue(client.refresh().registersDevices)
    assertEquals("obfk_k", getter.requests[0]["X-Overblast-Feedback-Key"])
    assertNull(getter.requests[0]["If-None-Match"])
    assertEquals("\"v1\"", store.kept!!.etag)
    now += 5 * 60_000L
    assertTrue(client.refresh().registersDevices) // 304 keeps it
    assertEquals("\"v1\"", getter.requests[1]["If-None-Match"])
  }

  @Test
  fun atMostEveryFiveMinutes() {
    var now = 0L
    val getter = FakeGetter(HttpAnswer(200, flag), HttpAnswer(200, flag))
    val client = KeyConfigClient("https://x/v1/feedback", "k", getter, MemStore(), "ua", { now })
    client.refresh()
    now += 60_000
    assertFalse(client.due())
    client.refresh()
    assertEquals(1, getter.requests.size)
    now += 4 * 60_000
    assertTrue(client.due())
    client.refresh()
    assertEquals(2, getter.requests.size)
  }

  @Test
  fun offlineUsesTheKeptAnswer() {
    val store = MemStore(StoredConfig(flag, "\"v1\"", 5))
    val client = KeyConfigClient("https://x/v1/feedback", "k", FakeGetter(IOException("offline")), store, "ua")
    // Kept before the door was asked, and still after it failed.
    assertTrue(client.config.registersDevices)
    assertTrue(client.refresh(force = true).registersDevices)
    assertFalse(client.config.screenshots)
  }

  @Test
  fun aRefusalOrNonsenseKeepsWhatWasThere() {
    val store = MemStore(StoredConfig(flag, null, 5))
    val client = KeyConfigClient("https://x/v1/feedback", "k", FakeGetter(HttpAnswer(401, """{"code":"unknown_key"}"""), HttpAnswer(200, "nope")), store, "ua")
    assertTrue(client.refresh(force = true).registersDevices)
    assertTrue(client.refresh(force = true).registersDevices)
  }
}
