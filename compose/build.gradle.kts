import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// COMPOSE IS A SEPARATE MODULE AND NOT A `compileOnly` SOURCE SET IN `:feedback`.
//
// A `compileOnly` Compose dependency would compile and ship nothing, right up to the moment an app
// without Compose touched the modifier and got a `NoClassDefFoundError` at runtime instead of a
// missing symbol at compile time. An optional dependency should be missing where a compiler can see
// it, so `Modifier.overblastPrivate()` and the semantics hit tester are their own artifact.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  `maven-publish`
}

android {
  namespace = "app.overblast.feedback.compose"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
    consumerProguardFiles("consumer-rules.pro")
  }

  buildFeatures {
    compose = true
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
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        artifactId = "compose"
      }
    }
  }
}
