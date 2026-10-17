// Platform-neutral module (Kotlin Multiplatform): recognises hand-drawn geometry and replaces
// it with clean shapes. Must not depend on Android APIs.
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
        commonMain.dependencies {
            api(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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
