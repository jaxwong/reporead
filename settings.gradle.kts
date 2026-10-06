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

rootProject.name = "RepoRead"
include(":reader-spike")
project(":reader-spike").projectDir = file("spikes/reader-android")
include(":backend")
include(":app")
project(":app").projectDir = file("android")
