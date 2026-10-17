pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NibSync"

include(":app")
include(":core:model")
include(":core:document")
include(":core:storage")
include(":ink:android")
include(":feature:canvas")
