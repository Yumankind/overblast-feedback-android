import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A plain-Views sample: one screen, a button that opens the report sheet, and a password field to show
// that it is blanked out of the screenshot. Built by `./gradlew build`; never published.
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "app.overblast.feedback.sample"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.overblast.feedback.sample"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "0.2.1"
    // Production unless `-PfeedbackEndpoint=http://10.0.2.2:8787/v1/feedback` points the sample at a
    // server on the machine running the emulator.
    resValue("string", "feedback_endpoint", (project.findProperty("feedbackEndpoint") as String?) ?: "https://brain.deployd.network/v1/feedback")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildTypes {
    // NO SIGNING CONFIG. `assembleRelease` produces an unsigned APK, which is all a build needs in
    // order to prove the sample compiles; signing it would mean a keystore in a public repository.
    release {
      isMinifyEnabled = false
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(project(":feedback"))
}
