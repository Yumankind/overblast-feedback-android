# overblast-feedback-android

Shake to report, for Android apps built on [Overblast](https://overblast.app). A person using your
app shakes the phone, says what is wrong, optionally points at the part of the screen that is wrong
and attaches a screenshot, and presses Send. The report lands in your Overblast workspace as a
**task**, with the selected view, the recent logs, the device, and the screenshot when they chose to
attach one.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        OverblastFeedback.start(this, key = "obfk_...")
    }
}
```

That is the whole integration. Shaking opens the sheet on whatever screen is showing.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.Yumankind.overblast-feedback-android:feedback:0.1.0")
    implementation("com.github.Yumankind.overblast-feedback-android:compose:0.1.0") // optional, Compose apps
    implementation("com.github.Yumankind.overblast-feedback-android:okhttp:0.1.0")  // optional, OkHttp apps
}
```

The version is a tag on this repository; JitPack builds it on first request. This repository has
three published modules, and JitPack serves a multi-module repository under
`com.github.<owner>.<repo>:<module>`, which is where the lines above come from. The shorter
`com.github.Yumankind:overblast-feedback-android:0.1.0` also resolves, but it brings **every** module,
Compose included, so name the modules you want instead.

`:feedback` depends on nothing but the Android framework and the Kotlin standard library: no AndroidX,
no Material, no OkHttp, no JSON library. The sheet is plain Views, the request is
`HttpURLConnection`, and the one permission it declares is `INTERNET`.

`minSdk 24`. Built with Kotlin 2.2 and published for Kotlin 2.0, so an app on Kotlin 1.9.20 or later
can use it. The bytecode targets Java 17, as every current Android Gradle Plugin does.

## The key

A feedback key (`obfk_...`) comes from the console's **Feedback keys** page. It is public by design,
like a Sentry DSN: it ships inside your app and grants nothing but "file a report into this
workspace". The worker holds it with caps (per key per day, per device, per IP) and you can revoke it
from the console. For a native-only key, leave its web origins empty.

## What the reporter sees

1. The screen is captured first, frozen, with private parts already blanked (see Privacy).
2. A sheet slides up: a category (Bug, Idea, Question, Other; Bug by default) and the message, which
   is the one required field.
3. **Point at the problem** shows the frozen screenshot full screen. A tap selects the deepest
   visible view under the finger and outlines it; **Use this** keeps it.
4. **Include screenshot** is off until they turn it on. When on, they see the image that will be sent
   (with the selected view outlined) and can remove it.
5. An email field, only when your app has not said who this is. It is optional and says why it is
   there: so the team can reply.
6. A line saying what will be sent: the message, the selected element, the screenshot, logs from this
   session, app and device details.
7. **Send.** A refusal is shown in the server's own sentence. The sheet follows the activity's
   light or dark mode.

## Options

```kotlin
OverblastFeedback.start(
    application = this,
    key = "obfk_...",
    endpoint = OverblastFeedback.DEFAULT_ENDPOINT,        // https://brain.deployd.network/v1/feedback
    reporter = Reporter(email = "ana@example.com", name = "Ana", accountRef = "cus_8812"),
    options = FeedbackOptions(
        shakeToReport = true,            // listen to the accelerometer while an activity is resumed
        shakeThresholdG = 2.7f,          // how hard a shake must be
        captureLogcat = true,            // add this process's own logcat
        sendLogs = true,                 // false sends no logs at all
        sendNetwork = true,              // the calls you record, see Network
        extra = mapOf("plan" to "pro"),  // up to 20 pairs into context.extra
        screenName = { activity -> router.currentRoute }, // default: the activity's class name
        strings = FeedbackStrings(title = "Envoyer un avis" /* ... */),
        onResult = { result -> analytics.track("feedback", result.toString()) },
    ),
)
```

| Call | What it does |
|---|---|
| `OverblastFeedback.show(activity)` | Opens the sheet: a "Report a problem" menu item, a debug button |
| `OverblastFeedback.setReporter(email, name, accountRef)` | Who is reporting; call on sign-in, and with nulls on sign-out |
| `OverblastFeedback.setExtra(key, value)` | One `context.extra` pair for later reports; `null` removes it |
| `OverblastFeedback.setShakeToReport(enabled)` | For an app with its own setting for shake |
| `OverblastFeedback.report(message, category, includeScreenshot, extra) { result -> }` | Files a report without the sheet |
| `OverblastFeedback.log(level, message)` | One line into the report's logs |
| `OverblastFeedback.recordNetwork(method, url, status, ms)` | One call into the report's network list |
| `OverblastFeedback.retryPending { sent, stillPending -> }` | Sends reports kept after a 429 |
| `OverblastFeedback.pendingCount` | How many are kept |

`accountRef` is your own id for the person (a customer id, a user id); it is shown on the task so the
team can find them in your systems. `email` is the one with a consequence, below.

## How reports appear in Overblast

Each report becomes a **task** in the workspace, titled from the first line of the message
(`Bug: The total doesn't update`, `Idea: ...`, `Question: ...`, `Feedback: ...`), tagged `feedback`,
the category, `android` and the key's own tags, and assigned to the key's group when it names one.
The description carries the message, the reporter, the account ref, the screen, the selected element
and the device. The screenshot and a `feedback-report.json` (element, logs, network, context) are
attached as the task's files.

**An email conversation is opened only when the report carries an email address**, either from
`setReporter` or typed into the sheet. No mail is sent when the report arrives: the address is
whatever the reporter typed, unverified. The thread starts when someone on the team replies from the
task. Without an email there is nobody to answer and no conversation, which is the right shape for
your own internal testers.

## Privacy

- **The screenshot is opt-in for every report.** It is off by default, the reporter sees it before
  sending, and can remove it.
- **Blanked before anyone sees it.** Views you mark are painted over with a solid box in the bitmap
  itself, and so are password fields (every password `inputType`, and any `TextView` showing a
  `PasswordTransformationMethod`) and fields whose autofill hints say card number, security code,
  password or one-time code, without being marked:

  ```kotlin
  binding.balance.overblastPrivate()
  ```

  ```kotlin
  Text(balance, Modifier.overblastPrivate())   // with the :compose module
  ```

- **A `FLAG_SECURE` window is never captured.** If your app has said a screen must not be
  screenshotted, the sheet opens without the screenshot and without Point at the problem.
- **The selected element's label** is its content description or its visible text, but never the
  text of an input, a password field or a private view; an input gives its hint instead.
- **Logs** are the last 500 lines, with values that look like bearer tokens, API keys (`sk-...`,
  `ob_live_...`, `obfk_...`), JWTs, Luhn-valid card numbers, and `password=` / `token=` / `api_key:`
  pairs replaced by `[redacted]` before they leave the device.
- **Network** entries carry the method, the URL without its query string and fragment, the status and
  the time. No headers, no bodies.
- The device id is a random UUID the library keeps in its own preferences, used only so the worker's
  per-device limit counts a phone rather than its network. No advertising id, no Android id.

## Tap to select

On a View-based screen the tap is hit-tested against the live view hierarchy under the sheet. The
element sent is

```jsonc
{
  "kind": "native",
  "path": ["DecorView", "LinearLayout", "FrameLayout", "CartView", "MaterialButton"],
  "type": "MaterialButton",
  "id": "remove_item",               // android:id's resource entry name
  "label": "Remove",                  // contentDescription, else text
  "rect": { "x": 0.61, "y": 0.42, "w": 0.2, "h": 0.05 },
  "viewport": { "w": 412, "h": 915 }  // dp
}
```

The path keeps its deepest 12 steps.

### Compose

A Compose screen is one view, `AndroidComposeView`, that draws everything itself, so the view walk
stops there. Add the `:compose` module and the library looks inside it by itself, with no setup: the
deepest semantics node under the tap, its `testTag` as `id`, its content description or text as
`label`, and its role (`Button`, `Checkbox`, `Tab`, ...), `Text` or `TextField` as `type`.

That module reads the semantics tree through `ViewRootForTest`, the interface Compose's own test
APIs use. It is public, but Compose marks it as meant for tests, so a future Compose release could
change it; if it does, selection falls back as below rather than failing.

Without the module, the library asks the Compose view's accessibility node provider instead. That
works on the root, but the platform only lists a node's children through an accessibility-service
connection an app does not have, so the children are reached through a hidden platform method. Where
Android refuses that, the selection is the Compose view as a whole. In that path a `testTag` only
appears as the element's `id` when `Modifier.semantics { testTagsAsResourceId = true }` is set on an
ancestor. Use the module.

A `WebView` is likewise one view: the selection is the WebView, not the element inside the page.

## Logs

Two sources, both redacted:

- `OverblastFeedback.log(LogLevel.ERROR, "checkout failed: $code")`, kept in a ring of the last
  500 lines (`src: "app"`);
- this process's own logcat, read when a report is sent (`logcat -d -t 500 --pid=<pid> -v epoch`,
  `src: "logcat"`). Android lets an app read its own lines without any permission. Logcat's V and D
  become `debug`, I `info`, W `warn`, and E, F and A `error`.

The two are merged by time, and the newest 500 that fit in 256 KB are sent. Turn logcat off with
`captureLogcat = false`, or every log off with `sendLogs = false`.

## Network

The library does not hook into any HTTP client by itself. Record the calls you want to see:

```kotlin
OverblastFeedback.recordNetwork("POST", "https://api.example.com/cart", 500, ms = 312)
```

With OkHttp, the `:okhttp` module's interceptor does it for every call (status 0 when the call failed
without an answer):

```kotlin
OkHttpClient.Builder()
    .addInterceptor(OverblastFeedbackInterceptor())
    .build()
```

That module declares OkHttp as compile-only, so it uses your app's OkHttp and adds no second copy.
The last 100 calls are kept.

## Results and errors

`onResult`, and the callback of `report`, get a `FeedbackResult`:

| Result | When |
|---|---|
| `Sent(id, taskId, conversationId, notice)` | Filed. `notice` is set on `507 storage_full`: the task exists, without its files |
| `SavedForLater(message, retryAfterSeconds)` | `429 rate_limited`: kept on the device |
| `Failed(status, code, message, field)` | Refused (`invalid_field`, `unknown_key`, `key_disabled`, `too_large`, `misconfigured`) or not answered (`status 0`, `code "network"`) |

Every `message` is a sentence that can be shown to the reporter as it is; the server's own sentence
is used whenever there is one.

A report refused with 429 is written to the app's files directory (at most 10 are kept) and tried
again once on the next `start`, or when you call `retryPending()`. It is never retried in a loop. A
report the server refuses for good (400, 401, 403, 413) on a retry is dropped.

`show`, `report` and `retryPending` throw `IllegalStateException` before `start`, and `start`
throws `IllegalArgumentException` for a key that is not an `obfk_` key, so a missing setup is found
on the first run rather than in a quiet absence of reports.

## Building from source

```bash
./gradlew test               # the JVM unit tests: payload, limits, redaction, anchors, logcat, retry
./gradlew assembleRelease    # the three AARs and the sample app (unsigned)
```

The Gradle wrapper is in the repository. Point the build at an Android SDK with `ANDROID_HOME`, or
with a `local.properties` (`sdk.dir=...`), which is ignored by git. The build uses JDK 17, AGP 8.11,
Kotlin 2.2 and `compileSdk 36`.

`samples/app` is a one-screen plain-Views app: a balance marked private, a password field, and a
button that opens the sheet. Put a key in its `res/values/strings.xml` to try it. It sends to
production; `./gradlew -PfeedbackEndpoint=http://10.0.2.2:8787/v1/feedback :samples:app:installDebug`
points a debug build at a server on the machine running the emulator instead.

## Releases

This repository is a mirror of a folder in the Overblast monorepo, where the library is developed
next to the worker that receives its reports. A release is a tag on this repository, and JitPack
builds whatever tag an app asks for.

## License

MIT. See [LICENSE](LICENSE).
