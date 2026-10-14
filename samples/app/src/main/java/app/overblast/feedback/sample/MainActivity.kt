package app.overblast.feedback.sample

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.overblast.feedback.LogLevel
import app.overblast.feedback.OverblastFeedback
import app.overblast.feedback.overblastPrivate

/** One screen: a balance marked private, a password field, and a button that opens the sheet. Shaking works too. */
class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = (16 * resources.displayMetrics.density).toInt()
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(pad, pad * 3, pad, pad)
      fitsSystemWindows = true
    }
    root.addView(TextView(this).apply {
      text = "Balance: 1,204.50 EUR"
      textSize = 22f
      overblastPrivate() // blanked in every screenshot
    })
    root.addView(EditText(this).apply {
      hint = "Password"
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD // blanked always
    })
    root.addView(Button(this).apply {
      id = R.id.remove_item
      text = "Remove item"
      setOnClickListener { OverblastFeedback.log(LogLevel.ERROR, "remove failed: total not updated (Authorization: Bearer abc.def.ghi)") }
    })
    root.addView(Button(this).apply {
      text = "Send feedback"
      setOnClickListener { OverblastFeedback.show(this@MainActivity) }
    })
    setContentView(root)
    OverblastFeedback.log(LogLevel.INFO, "MainActivity created")
  }
}
