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
    implementation("com.github.Yumankind.overblast-feedback-android:feedback:0.2.0")
    implementation("com.github.Yumankind.overblast-feedback-android:compose:0.2.0")           // optional
    implementation("com.github.Yumankind.overblast-feedback-android:compose-material3:0.2.0") // optional
    implementation("com.github.Yumankind.overblast-feedback-android:okhttp:0.2.0")            // optional
}
```

| Artifact | Add it when | What it brings |
|---|---|---|
| `feedback` | always | The library. No AndroidX, Material, OkHttp or JSON library |
| `compose` | the app has Compose screens | Tap-to-select inside Compose, `Modifier.overblastPrivate()`; Compose UI, which the app has |
| `compose-material3` | the app uses a Material 3 `MaterialTheme` and wants the sheet in its colours | `FeedbackTheme.fromMaterialTheme()`; Material 3, which the app has |
| `okhttp` | the app uses OkHttp | The interceptor that records calls; uses the app's own OkHttp |

The version is a tag on this repository; JitPack builds it on first request. This repository has
four published modules, and JitPack serves a multi-module repository under
`com.github.<owner>.<repo>:<module>`, which is where the lines above come from. The shorter
`com.github.Yumankind:overblast-feedback-android:0.2.0` also resolves, but it brings **every** module,
Compose and Material 3 included, so name the modules you want instead.

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

Some behaviour is set on the key in the console rather than in code, so it can change without a new
build. At start, and at most every five minutes after, the library reads the key's settings
(`GET <endpoint>/config`), keeps the last answer on the device with its ETag, and uses the kept answer
when the door cannot be reached. Before it has ever had an answer it uses the defaults:

| Setting on the key | Default | What it does here |
|---|---|---|
| Device verification | `off` | `flag` or `require` turns on Device verification, below |
| Screenshots | on | off removes the "Include screenshot" switch (Point at the problem stays) |
| Error prompt | off | on allows the prompt in The error prompt, below |
| Categories | all four | the kinds the sheet offers, the first as its default; with only Bug the sheet has no choice and is titled "Report a problem", and `report()` with another category returns `Failed(403, "category_not_accepted")` unsent |

Your options can only turn these **off** for your app (`deviceVerification = false`,
`errorPrompt = false`); they cannot turn on what the key has off.

## What the reporter sees

1. The screen is captured first, frozen, with private parts already blanked (see Privacy).
2. A bottom sheet opens half way. Its handle drags it to full height (or down to close), and it goes
   full height by itself when the keyboard comes up. **Send** sits at the sheet's bottom, so it is
   above the keyboard at all times.
3. At the top, the categories the key takes (Bug, Idea, Question, Other by default; none when the
   key takes one), then a large message field, the one required field.
4. **Point at the problem** shows the frozen screen full screen under "Tap the part that's wrong". A
   tap outlines the view under the finger and dims the rest; **Use this** confirms it. Back in the
   sheet, the pick is a thumbnail of that part of the screen with its label, **Change** and **Remove**.
5. **Include screenshot** is off until they turn it on. Then they see a preview of the image that
   will be sent; tapping it opens an editor to drag rectangles that **cover** (solid) or **blur** parts
   of it, with **Undo** and **Clear**. Private areas are blanked in the capture itself and cannot be
   uncovered.
6. An email field, only when your app has not said who this is. It is optional and says why it is
   there: so the team can reply.
7. A line saying what will be sent: the message, the selected element, the screenshot, logs from this
   session, app and device details.
8. **Send.** On success the sheet shows a check and "Sent, thank you" for a moment (or "Thanks. We
   already know about this one and we're on it." when the team already has it), TalkBack announces
   it, and the sheet closes. A refusal keeps the sheet open with the server's own sentence.

TalkBack reads every control (the categories as radio buttons, the handle with expand and collapse),
text follows the person's font size, and with animations turned off on the phone nothing slides or
fades.

## The look

Everything the library draws (the sheet, its chips and buttons, the pointer and editor screens, the
error prompt and the crash question) takes your brand from one `FeedbackTheme`; light or dark follows
the activity.

```kotlin
FeedbackOptions(theme = FeedbackTheme(accent = 0xFF6D28D9.toInt(), typeface = brandFont, cornerRadiusDp = 20f))
```

With no accent, the activity theme's `colorPrimary` is used (AppCompat and Material Components set
it), then `android:colorPrimary`, then `android:colorAccent`, skipping a colour that would vanish on
the sheet; text on the accent is white or black, whichever reads better. In a Compose app with a
Material 3 theme, the `compose-material3` artifact reads it for you:

```kotlin
import app.overblast.feedback.compose.material3.fromMaterialTheme

val theme = FeedbackTheme.fromMaterialTheme()   // primary colour, body font, large shape's corner
LaunchedEffect(theme) { OverblastFeedback.setTheme(theme) }
```

`OverblastFeedback.setTheme(theme)` changes the brand at runtime (null goes back to the options').
Material 3 lives only in `compose-material3`, so an app without it never meets a Material class.

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
        captureLogcat = true,            // follow this process's own logcat into the logs
        sendLogs = true,                 // false sends no logs at all
        sendNetwork = true,              // the calls you record, see Network
        extra = mapOf("plan" to "pro"),  // up to 20 pairs into context.extra
        screenName = { activity -> router.currentRoute }, // default: the activity's class name
        strings = FeedbackStrings(title = "Envoyer un avis" /* ... */),
        onResult = { result -> analytics.track("feedback", result.toString()) },
        deviceVerification = true,       // register this phone and sign reports, see Device verification
        offerReportAfterCrash = true,    // ask once after an unexpected end, see After a crash
        errorPrompt = true,              // allow the error prompt when the key has it on
        appHosts = listOf("api.example.com"), // your own API hosts, for the error prompt
        theme = FeedbackTheme(accent = 0xFF6D28D9.toInt()), // see The look
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
| `OverblastFeedback.log(LogLevel.ERROR, message, unexpected = true)` | An error line that also counts for the error prompt |
| `OverblastFeedback.recordError(throwable)` | An exception you caught but did not expect: logged, and counted for the error prompt |
| `OverblastFeedback.recordNetwork(method, url, status, ms)` | One call into the report's network list |
| `OverblastFeedback.retryPending { sent, stillPending -> }` | Sends reports kept after a 429 |
| `OverblastFeedback.pendingCount` | How many are kept |
| `OverblastFeedback.setTheme(theme)` | The brand for what the library draws from now on; see The look |
| `OverblastFeedback.deviceLevel` | `ATTESTED`, `REGISTERED`, `NONE`, or null until known; see Device verification |

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
- **Logs** have values that look like bearer tokens, API keys (`sk-...`, `ob_live_...`, `obfk_...`),
  JWTs, Luhn-valid card numbers, and `password=` / `token=` / `api_key:` pairs replaced by
  `[redacted]` as they are kept, before they are written to disk or leave the device. A crash's
  message and stack are redacted the same way. The disk copy lives in the app's own files directory,
  which no other app can read, and is deleted at the next launch unless that session ended
  unexpectedly (then once its report is offered).
- **Network** entries carry the method, the URL without its query string and fragment, the status and
  the time. No headers, no bodies.
- The device id is a random UUID the library keeps in its own preferences, used only so the worker's
  per-device limit counts a phone rather than its network. No advertising id, no Android id.
- The device key (see Device verification) is made for this library alone, under its own keystore
  alias, and its attestation names your app and its signing certificate, nothing about the person.

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

On a Compose screen the semantics come first: the deepest node under the tap that has a `testTag`,
a content description or text wins, even when the screen hosts an `AndroidView` (a map, a camera
preview), whose full-screen `AndroidViewsHandler` the view walk always ends in. When no such node is
under the tap and the tap is on a hosted `AndroidView`, the selection is that view (the `MapView`,
not the handler or its inner `TextureView`), or a named view inside it. The handler and the holder
around each `AndroidView` are never selected.

A `WebView` is likewise one view: the selection is the WebView, not the element inside the page.

## Logs

Two sources, one intake:

- `OverblastFeedback.log(LogLevel.ERROR, "checkout failed: $code")` (`src: "app"`);
- this process's own logcat, followed for the life of the app by one `logcat -v epoch --pid=<pid>`
  process (`src: "logcat"`). Android lets an app read its own lines without any permission. Logcat's
  V and D become `debug`, I `info`, W `warn`, and E, F and A `error`.

Every line goes through the same rules, so a log storm costs the app little and still fits a report:

- **Repeats collapse.** A line identical to the one before (level, source, text) is kept once, with
  `repeat` (how many times it occurred) and `last` (the time of the last one).
- **At most 50 lines a second** enter; warnings and errors up to 200 a second. The rest are counted,
  and one line marks the gap: `1,204 lines dropped in 3 s (too fast)`.
- **Only kept lines are redacted** (see Privacy), so a storm of dropped lines costs a counter.
- **The buffer holds 20,000 lines and 2 MB.** When it is full the oldest debug and info lines go
  first; an error from five minutes ago outlives a thousand debug lines from the last second.
- **Nothing is collected while the sheet is open**, so the sheet's own activity never fills its report.
- **A copy is kept on disk** in the app's own files directory (at most 4 MB in two rotating files),
  written every few seconds and at once after an error, so the lines before a crash survive it.

A report carries two parts: `logs`, an excerpt of at most 500 entries and 256 KB (every warning and
error in the two minutes before the report was opened, then the newest other lines), and `logFile`,
everything the buffer holds as gzip-compressed NDJSON (at most 4 MB; the oldest lines are cut when it
would be larger, and its first line says so). The file is left out when the excerpt already holds
every line. A report with a screenshot and a log file still fits the 8 MB body: the log file gives
way first, then the screenshot.

Turn logcat off with `captureLogcat = false`, or every log off with `sendLogs = false` (no buffer, no
disk copy, no logcat process).

## After a crash

When the app's last session ended while it was in front of the person (a crash, an ANR, the system
killing it for memory), the next launch asks once, on the first screen, "The app closed unexpectedly.
Send a report?". **Send report** opens the sheet with that session's saved logs and a `crash` section;
**Not now** discards it. The task is titled `Crash: <message>`.

How the library knows:

- a session marker on disk, open while one of the app's activities is started and closed when the last
  one stops. An app the system kills in the background (which Android does all the time) closed its
  session first, so it is never reported;
- its own default uncaught-exception handler, which writes the exception (message and redacted stack)
  and the log to disk synchronously, then hands the crash to the handler that was installed before it,
  so the app crashes exactly as it would have and any crash reporter you use still sees it;
- on Android 11 and later, `ActivityManager.getHistoricalProcessExitReasons`: a crash or native crash
  is `crash`, an ANR `killed`, low memory `oom`, anything else `unknown`. A stop the person asked for
  (a force stop, or stopping the app from settings) is not a crash and is not offered.

A session that ended with no record from either is sent as `kind: "unknown"`. Turn the question off
with `FeedbackOptions(offerReportAfterCrash = false)`; the saved session is then discarded unasked.

Every report also says when it was opened (`openedAt`) and how (`trigger`: `shake`, `button` for your
own `show()`, `api` for `report()`, `relaunch` for the offer), so the task can say what failed just
before.

## The error prompt

When the key has the error prompt on (it is off by default), the library may show a small pill at the
bottom of the screen after something goes wrong: "Something may have gone wrong. Tell us?", with
**Report** and a close button, in the app's light or dark. **Report** opens the sheet as a bug, so the
error that set it off is among the lines just before the report.

What counts is what the key lists: `exception` is an error you mark (`log(ERROR, message, unexpected =
true)` or `recordError(e)`; an uncaught exception closes the app, and the next launch offers the
report instead), `server_error` a request to one of your `appHosts` answered 500 to 599, and `network`
one that never answered. Requests come from `recordNetwork` or the OkHttp interceptor; with no
`appHosts`, no request counts, and the feedback door itself never does.

It stays out of the way: not in the first 10 seconds, not while the person is typing (the keyboard
is up, or a text field changed in the last 5 seconds; a field that only has focus does not count),
not while the sheet, the crash question or another dialog is showing, and only while an
activity is in front (it waits up to 30 seconds for that, then gives up). It shows at most once per
launch and once per the key's `minHoursBetween` hours, and after two closes without a report not for
seven days. It goes by itself after about 8 seconds. TalkBack reads it politely, and with animations
off on the phone it appears without fading. Each prompt leaves a line in the logs (`error prompt
shown`, `dismissed`), so a report says whether the person had been prompted.

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

## Device verification

When the key's device verification is `flag` or `require` in the console (it is `off` by default,
and then nothing below happens and reports go unsigned), the library registers the phone with
Overblast once, on the first report, and from then on it signs every report with that phone's key. The worker's per-device limits then count real phones, and a
verified phone gets the larger allowance (contract `docs/feedback-reports.md` §4 and §8 in the
Overblast repository).

**What it proves.** The library makes an EC P-256 key in the Android Keystore, in StrongBox where the
phone has one and otherwise in its trusted execution environment, and asks the keystore to attest it.
The attestation is a certificate chain made by the phone's secure hardware and signed up to one of
Google's public roots. It says the key cannot leave that hardware, that the bootloader is locked, and
which app asked for it: the package name and the SHA-256 of its signing certificate. When all of that
matches an app listed on your feedback key, the phone is `attested`. No Play Integrity, no Google
Cloud project and no secret are involved: everything the worker checks against is public.

**What you do.** Nothing in code; `deviceVerification = true` is the default. In the console, on the
feedback key, set device verification to `flag` (or `require`) and add your app under Android apps:

- the package name (`com.example.app`);
- the SHA-256 fingerprint of the **app signing** certificate, from Play Console's App signing page
  (under App integrity). Google re-signs what Play delivers, so this is the certificate on users'
  phones;
- for internal builds you install yourself (debug builds, Firebase App Distribution, a sideloaded
  release), also the fingerprint of the certificate those builds are signed with, the upload key or
  the debug key (`./gradlew signingReport` prints both).

Without that, the reports still arrive: the phone registers as `registered` instead.

**How it works.** When the sheet opens, the library starts registering in the background, so it is
usually done by the time the reporter presses Send; Send waits at most three seconds more and then
sends the report unsigned. A phone that cannot attest a key, or whose attestation the worker refuses,
solves a short proof of work instead (at most 20 seconds, on a background thread) and registers a
plain keystore key. The device id, the method and the level are kept in the library's own
preferences; the key never leaves the keystore. Each report is signed at the moment it is sent, over
the exact bytes sent, including a report kept after a 429 and sent later.

If the worker no longer knows the device, or the key has gone (an Auto Backup restore onto a new phone
brings the preferences but never the keystore), the library registers again and resends once. If your
key is set to require a verified device and registration failed, the report is refused with the
worker's sentence.

```kotlin
OverblastFeedback.deviceLevel   // ATTESTED, REGISTERED, NONE, or null until the first registration
```

**Limits.** A phone with an unlocked bootloader, a phone without secure hardware attestation, and
every emulator register as `registered`, not `attested`: their attestation is either missing or
signed by a software keystore. A rooted phone with a locked bootloader is not detected by attestation
alone. `deviceVerification = false` turns all of it off and sends every report unsigned, which a key
set to require a device refuses.

## Results and errors

`onResult`, and the callback of `report`, get a `FeedbackResult`:

| Result | When |
|---|---|
| `Sent(id, taskId, conversationId, notice, known)` | Filed. `notice` is set on `507 storage_full`: the task exists, without its files. `known` is true when the team already has this problem |
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
./gradlew test               # the JVM unit tests: payload, limits, redaction, anchors, logcat, retry, device signing
./gradlew assembleRelease    # the four AARs and the sample app (unsigned)
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
