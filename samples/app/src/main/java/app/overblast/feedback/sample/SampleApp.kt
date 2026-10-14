package app.overblast.feedback.sample

import android.app.Application
import app.overblast.feedback.FeedbackOptions
import app.overblast.feedback.OverblastFeedback

class SampleApp : Application() {
  override fun onCreate() {
    super.onCreate()
    OverblastFeedback.start(
      application = this,
      key = getString(R.string.feedback_key),
      endpoint = getString(R.string.feedback_endpoint),
      options = FeedbackOptions(extra = mapOf("sample" to "true")),
    )
    // Uncomment when the app knows who is signed in; the sheet then stops asking for an email.
    // OverblastFeedback.setReporter(email = "ana@example.com", name = "Ana", accountRef = "cus_8812")
  }
}
