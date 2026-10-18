// Text box logic shared by the Android app and the PC app: conversion between the stored
// RichText and the editor library, the editor controller, page layout of paragraphs and the
// embedded fonts. The rich text editor library stays inside this module and feature/text.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "com.stemaker.nibsync.text.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        androidResources {
            enable = true
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:document"))
            api(compose.runtime)
            api(compose.ui)
            api(compose.foundation)
            api(libs.richeditor.compose)
        }
        // The PC loads the same font files from the classpath.
        jvmMain {
            resources.srcDir("src/androidMain/res/font")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.truth)
        }
    }
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
