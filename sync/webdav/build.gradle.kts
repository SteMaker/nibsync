// RemoteStore for Nextcloud/WebDAV and the Nextcloud login flow. JVM code (OkHttp), usable by
// the Android app and a later desktop client.
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
            api(project(":core:sync"))
            api(libs.okhttp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okio.fakefilesystem)
            implementation(libs.truth)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Lets `./gradlew test` at the root include this module, like the Android modules.
tasks.register("test") {
    dependsOn("jvmTest")
}

tasks.withType<Test>().configureEach {
    // Integration test against a real Nextcloud runs only when configured (see NextcloudIntegrationTest).
    for (name in listOf("NIBSYNC_NEXTCLOUD_URL", "NIBSYNC_NEXTCLOUD_USER", "NIBSYNC_NEXTCLOUD_PASSWORD")) {
        System.getenv(name)?.let { environment(name, it) }
    }
}
