package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
  @Test
  fun escapesWhatJsonRequires() {
    val s = "quote \" backslash \\ newline \n tab \t ctrl \u0001 line-sep   é 😀"
    val text = Json.encode(mapOf("s" to s))
    assertEquals("{\"s\":\"quote \\\" backslash \\\\ newline \\n tab \\t ctrl \\u0001 line-sep \\u2028 é 😀\"}", text)
    assertEquals(mapOf("s" to s), Json.parse(text))
  }

  @Test
  fun numbersAreWrittenWithoutExponents() {
    assertEquals("[1,0.0001,2.625,1760440000123,0]", Json.encode(listOf(1, 0.0001, 2.625, 1760440000123L, Double.NaN)))
  }

  @Test
  fun nullEntriesAreLeftOutOfObjects() {
    assertEquals("{\"a\":1}", Json.encode(linkedMapOf("a" to 1, "b" to null)))
  }

  @Test
  fun parsesTheWorkersAnswers() {
    val m = Json.parse("""{ "id": "fbr_1", "taskId": "t1", "conversationId": null, "n": [1, 2.5, -3e2], "ok": true }""") as Map<*, *>
    assertEquals("fbr_1", m["id"])
    assertEquals(null, m["conversationId"])
    assertEquals(listOf(1.0, 2.5, -300.0), m["n"])
    assertEquals(true, m["ok"])
  }
}
