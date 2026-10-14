package app.overblast.feedback.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
  private fun r(s: String) = Redactor.redact(s)

  @Test
  fun bearerTokens() {
    assertEquals("Authorization: Bearer [redacted]", r("Authorization: Bearer abcDEF123.456-_~+/="))
  }

  @Test
  fun apiKeys() {
    assertEquals("key [redacted] end", r("key sk-proj-ABCDEFGHIJKLmnop1234 end"))
    assertEquals("[redacted]", r("ob_live_0123456789abcdef"))
    assertEquals("using [redacted]", r("using obfk_ABCDEFGHIJKLMNOPQRSTUVWX"))
  }

  @Test
  fun jwts() {
    val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    assertEquals("token is [redacted]", r("token is $jwt").replace("token is", "token is"))
    assertFalse(r("jwt=$jwt").contains("eyJ"))
  }

  @Test
  fun luhnValidCardNumbersOnly() {
    assertEquals("card [redacted] ok", r("card 4242 4242 4242 4242 ok"))
    assertEquals("card [redacted]", r("card 5555-5555-5555-4444"))
    assertEquals("card [redacted]", r("card 378282246310005"))
    // Not Luhn-valid: left alone.
    assertEquals("order 4242424242424241", r("order 4242424242424241"))
    // A millisecond timestamp starts with 1 and is never taken for a card.
    assertEquals("t=1760440000123", r("t=1760440000123"))
  }

  @Test
  fun secretPairs() {
    assertEquals("password=[redacted]&user=ana", r("password=hunter2&user=ana"))
    assertEquals("{\"token\": \"[redacted]\", \"a\": 1}", r("{\"token\": \"abc def\", \"a\": 1}"))
    assertEquals("api_key: [redacted]", r("api_key: 12345"))
    assertTrue(r("refresh_token=xyz").endsWith("[redacted]"))
  }

  @Test
  fun ordinaryLinesAreUntouched() {
    val s = "GET /v1/cart 200 in 312 ms, items=3"
    assertEquals(s, r(s))
  }
}
