// The app's look, shared by the Android app and the PC app: colour schemes (light and dark,
// neutral greys) and the few colours of the page chrome that Material has no slot for.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "com.stemaker.nibsync.theme"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
        }
    }
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
