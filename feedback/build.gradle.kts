import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// THE LIBRARY. It depends on nothing but the Android framework and the Kotlin standard library: no
// AndroidX, no Material, no OkHttp, no JSON library. The dialog is plain Views, the request is
// HttpURLConnection, and the JSON is written by a small encoder in this module, so adding it to an
// app adds this AAR and nothing else.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  `maven-publish`
}

android {
  namespace = "app.overblast.feedback"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
    consumerProguardFiles("consumer-rules.pro")
    buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
  }

  buildFeatures {
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    // The pure-Kotlin core is what the unit tests exercise, and it never touches `android.*`. This
    // only stops a stray framework call in a test from failing with "Method ... not mocked".
    unitTests.isReturnDefaultValues = true
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

kotlin {
  explicitApi()
  // Built with Kotlin 2.2, published for Kotlin 2.0: an app on any Kotlin from 1.9.20 up can read the
  // metadata, and the POM asks for kotlin-stdlib 2.0.21 rather than pulling the app's up to ours.
  coreLibrariesVersion = "2.0.21"
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    apiVersion.set(KotlinVersion.KOTLIN_2_0)
    languageVersion.set(KotlinVersion.KOTLIN_2_0)
  }
}

dependencies {
  testImplementation(libs.junit)
}

// `components["release"]` only exists once AGP has created the variant, so the publication is
// registered after evaluation. This is the canonical shape from AGP's own documentation.
afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        // groupId and version are the PROJECT's, which gradle.properties sets and JitPack
        // overrides on the command line. Only the artifactId is stated here.
        artifactId = "feedback"
        pom {
          name.set("overblast-feedback-android")
          description.set("Shake to report: feedback with a screenshot, the selected view and logs, filed as an Overblast task.")
          url.set("https://overblast.app")
          licenses {
            license {
              name.set("MIT License")
              url.set("https://github.com/Yumankind/overblast-feedback-android/blob/main/LICENSE")
            }
          }
        }
      }
    }
  }
}
