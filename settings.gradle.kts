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

rootProject.name = "TejerFacil"

// :core  -> visión artificial (Kotlin/JVM puro + OpenCV), probado en la JVM.
// :app   -> aplicación Android (Compose, Room, PdfRenderer).
include(":core")
include(":app")
