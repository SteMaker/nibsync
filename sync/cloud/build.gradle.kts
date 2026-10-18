// OneDrive and Google Drive as RemoteStores for the sync, with their OAuth logins. JVM code
// (OkHttp), used by the Android app and the PC app.
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
        jvmMain.dependencies {
            api(project(":sync:webdav"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.truth)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
            implementation(project(":core:library"))
        }
    }
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
