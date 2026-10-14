package app.overblast.feedback.internal

import app.overblast.feedback.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogcatParserTest {
  @Test
  fun parsesEpochFormat() {
    val e = LogcatParser.parseLine("1760440000.123  4123  4140 E OkHttp  : <-- HTTP FAILED: java.net.SocketTimeoutException")!!
    assertEquals(1760440000123L, e.t)
    assertEquals(LogLevel.ERROR, e.level)
    assertEquals("OkHttp: <-- HTTP FAILED: java.net.SocketTimeoutException", e.msg)
    assertEquals("logcat", e.src)
  }

  @Test
  fun mapsEveryPriority() {
    val levels = mapOf("V" to LogLevel.DEBUG, "D" to LogLevel.DEBUG, "I" to LogLevel.INFO, "W" to LogLevel.WARN, "E" to LogLevel.ERROR, "F" to LogLevel.ERROR, "A" to LogLevel.ERROR)
    for ((p, l) in levels) assertEquals(p, l, LogcatParser.parseLine("   1760440000.5 1 2 $p Tag: m")!!.level)
  }

  @Test
  fun fractionDigitsBecomeMilliseconds() {
    assertEquals(1760440000500L, LogcatParser.parseLine("1760440000.5 1 2 I T: m")!!.t)
    assertEquals(1760440000123L, LogcatParser.parseLine("1760440000.123456 1 2 I T: m")!!.t)
  }

  @Test
  fun skipsBannersAndRedacts() {
    assertNull(LogcatParser.parseLine("--------- beginning of main"))
    assertNull(LogcatParser.parseLine(""))
    val out = LogcatParser.parse(
      """
      --------- beginning of main
      1760440000.100  10  11 I Net: Authorization: Bearer abcdefghijklmnop
      1760440000.200  10  11 W Net: retrying with password=hunter2
      1760440000.300  10  11 D Empty:${" "}
      """.trimIndent(),
    )
    assertEquals(3, out.size)
    assertEquals("Net: Authorization: Bearer [redacted]", out[0].msg)
    assertEquals("Net: retrying with password=[redacted]", out[1].msg)
    assertEquals("Empty: ", out[2].msg)
  }

  @Test
  fun keepsTheLastLines() {
    val text = (1..600).joinToString("\n") { "1760440000.${(it % 1000).toString().padStart(3, '0')} 1 2 I T: line $it" }
    val out = LogcatParser.parse(text)
    assertEquals(500, out.size)
    assertEquals("T: line 600", out.last().msg)
  }
}
