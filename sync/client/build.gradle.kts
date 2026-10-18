// Running the sync on a device: account, status, the periodic and change-triggered runs.
// Shared by the Android app and the PC app; platform parts (where the login is stored, the
// open documents) come in through SyncHost and SyncConfigStore.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":sync:webdav"))
            api(project(":core:library"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.truth)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
