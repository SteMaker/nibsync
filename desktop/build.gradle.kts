// The PC application (Linux, Windows, later macOS), built with Compose Multiplatform for
// Desktop on top of the platform-neutral core modules.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain {
            // The fonts embedded in the Android app (feature/text), loaded from the classpath.
            resources.srcDir("../feature/text/src/main/res/font")
            dependencies {
                implementation(project(":core:library"))
                implementation(project(":core:document"))
                implementation(project(":core:storage"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.truth)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

compose.desktop {
    application {
        mainClass = "de.nibsync.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "nibsync"
            packageVersion = "1.0.0"
        }
    }
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
