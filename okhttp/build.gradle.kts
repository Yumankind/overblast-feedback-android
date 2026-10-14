import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// ONE INTERCEPTOR, AND WHY OKHTTP IS `compileOnly`.
//
// An app that would add this module already uses OkHttp. Declaring it as a runtime dependency would
// put a second, possibly older, copy on the classpath of every app that adds us, so it is on the
// compile classpath only: the published POM names `:feedback` and nothing else, and the app's own
// OkHttp is the one that runs.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  `maven-publish`
}

android {
  namespace = "app.overblast.feedback.okhttp"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
  api(project(":feedback"))
  compileOnly(libs.okhttp)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        artifactId = "okhttp"
      }
    }
  }
}
