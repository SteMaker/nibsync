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
include(":core:shapes")
include(":core:library")
include(":core:sync")
include(":sync:webdav")
include(":feature:library")
include(":ink:android")
include(":feature:canvas")
include(":ui:background")
include(":shared:text")
include(":feature:text")
include(":feature:media")
include(":desktop")
