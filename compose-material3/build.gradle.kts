import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// MATERIAL 3 IS A MODULE OF ITS OWN, so that no other artifact refers to it. `FeedbackTheme.fromMaterialTheme()`
// reads the app's Material 3 theme; an app that adds this module has Material 3 already, and an app
// that does not never meets a Material 3 class (a compile-only dependency in `:compose` would have
// crashed such an app with NoClassDefFoundError the moment it called the helper).
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  `maven-publish`
}

android {
  namespace = "app.overblast.feedback.compose.material3"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    // The tests exercise pure decisions; this only stops a stray framework call from failing them with
    // "Method ... not mocked".
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
  coreLibrariesVersion = "2.0.21"
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    apiVersion.set(KotlinVersion.KOTLIN_2_0)
    languageVersion.set(KotlinVersion.KOTLIN_2_0)
  }
}

dependencies {
  api(project(":compose"))
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  testImplementation(libs.junit)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        artifactId = "compose-material3"
      }
    }
  }
}
