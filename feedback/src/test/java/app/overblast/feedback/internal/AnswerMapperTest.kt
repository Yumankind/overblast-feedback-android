package app.overblast.feedback.internal

import app.overblast.feedback.FeedbackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMapperTest {
  @Test
  fun createdCarriesTheIds() {
    val r = AnswerMapper.map(201, """{"id":"fbr_1","taskId":"t_9","conversationId":null}""")
    assertEquals(FeedbackResult.Sent("fbr_1", "t_9", null), r)
  }

  @Test
  fun refusalsUseTheServersSentenceAndCode() {
    val r = AnswerMapper.map(400, """{"code":"invalid_field","field":"context.platform","error":"platform must be one of web, ios, android, flutter."}""")
    assertEquals(FeedbackResult.Failed(400, "invalid_field", "platform must be one of web, ios, android, flutter.", "context.platform"), r)
  }

  @Test
  fun everyStatusOfTheContractMaps() {
    val cases = mapOf(
      401 to "unknown_key",
      403 to "key_disabled",
      413 to "too_large",
      429 to "rate_limited",
      500 to "misconfigured",
    )
    for ((status, code) in cases) {
      val r = AnswerMapper.map(status, "<html>proxy error</html>") as FeedbackResult.Failed
      assertEquals(code, r.code)
      assertTrue(r.message.isNotBlank())
    }
    val origin = AnswerMapper.map(403, """{"code":"origin_not_allowed","error":"Not from here."}""") as FeedbackResult.Failed
    assertEquals("origin_not_allowed", origin.code)
    assertEquals("Not from here.", origin.message)
  }

  @Test
  fun rateLimitedCarriesRetryAfter() {
    val r = AnswerMapper.map(429, """{"code":"rate_limited","error":"Too many reports from this device.","retryAfter":1800}""") as FeedbackResult.Failed
    assertEquals(1800L, r.retryAfterSeconds)
    assertEquals("Too many reports from this device.", r.message)
  }

  @Test
  fun storageFullIsFiledWithANotice() {
    val r = AnswerMapper.map(507, """{"code":"storage_full","error":"Filed without the screenshot: the workspace is out of storage.","taskId":"t1"}""")
    assertEquals(FeedbackResult.Sent(null, "t1", null, "Filed without the screenshot: the workspace is out of storage."), r)
  }

  @Test
  fun aKnownIssueIsSaidOnTheResult() {
    assertEquals(FeedbackResult.Sent("fbr_1", "t", null, known = true), AnswerMapper.map(201, """{"id":"fbr_1","taskId":"t","conversationId":null,"known":true}"""))
    assertEquals(false, (AnswerMapper.map(201, """{"id":"fbr_1","taskId":"t","conversationId":null}""") as FeedbackResult.Sent).known)
  }
}
