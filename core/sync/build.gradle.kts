// Platform-neutral module (Kotlin Multiplatform): file-based synchronisation of documents and
// library with a remote folder (Nextcloud/WebDAV via an implementation of RemoteStore).
// Must not depend on Android APIs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:library"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okio.fakefilesystem)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.truth)
        }
    }
}

// Lets `./gradlew test` at the root include this module, like the Android modules.
tasks.register("test") {
    dependsOn("jvmTest")
}
