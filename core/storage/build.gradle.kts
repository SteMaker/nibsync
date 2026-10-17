// Platform-neutral module (Kotlin Multiplatform). Must not depend on Android APIs so the
// same code can later back a Windows/desktop client. Only the JVM target exists for now;
// Android consumes the JVM variant.
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
            api(project(":core:document"))
            api(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
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
