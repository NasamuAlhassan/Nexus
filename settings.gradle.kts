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

rootProject.name = "Tekyerema"
include(":app")
// The debug USSD simulator — a SEPARATE tiny APK (its own package): the accessibility service is shown no windows of its own
// package, so a simulator inside the app could never be read (13 October 2026). Never shipped; emulator testing only.
include(":ussdsim")
