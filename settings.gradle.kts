// The public repository https://github.com/Yumankind/overblast-feedback-android is a mirror of this
// directory (scripts/mirror.sh), so THIS FILE IS THE ROOT OF THE PUBLISHED GRADLE BUILD. Nothing
// above it travels, which is why there is no reference to the monorepo anywhere in the build.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "overblast-feedback-android"

// `:feedback` is the library and the only module an app needs. `:compose`, `:compose-material3` and
// `:okhttp` are small optional add-ons, each its own artifact so that neither Compose, Material 3 nor
// OkHttp is ever forced on an app that does not already use it. The sample is built here but never
// published.
include(":feedback")
include(":compose")
include(":compose-material3")
include(":okhttp")
include(":samples:app")
