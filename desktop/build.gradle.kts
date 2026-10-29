// The PC application (Linux, Windows, later macOS), built with Compose Multiplatform for
// Desktop on top of the platform-neutral core modules.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// OAuth clients for OneDrive / Google Drive from the untracked cloud.properties (see
// cloud.properties.example), packed into the app as a resource.
val cloudClients = tasks.register<Copy>("cloudClients") {
    from(rootProject.file("cloud.properties").takeIf { it.exists() } ?: rootProject.file("cloud.properties.example"))
    rename { "nibsync-cloud.properties" }
    into(layout.buildDirectory.dir("generated/cloud"))
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain {
            resources.srcDir(cloudClients)
            dependencies {
                implementation(project(":shared:text"))
                implementation(project(":shared:theme"))
                implementation(project(":sync:client"))
                implementation(project(":core:library"))
                implementation(project(":core:document"))
                implementation(project(":core:storage"))
                implementation(project(":core:shapes"))
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
            implementation(libs.okhttp.mockwebserver)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.stemaker.nibsync.desktop.MainKt"
        // Notes need little memory; without a cap the JVM may take a quarter of the RAM.
        jvmArgs += listOf("-Xmx1g")
        nativeDistributions {
            // Each format is built on its own system: .deb/.rpm on Linux, .msi on Windows.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "nibsync"
            // From the release tag (CI sets NIBSYNC_VERSION), else a local build.
            packageVersion = providers.environmentVariable("NIBSYNC_VERSION").getOrElse("1.0.0")
            description = "Handwritten notes in books and chapters, synced with Nextcloud, OneDrive or Google Drive"
            vendor = "Stefan Macher"
            copyright = "© 2026 Stefan Macher"
            // Only what the app uses of the JDK, to keep the package small.
            // (suggestRuntimeModules, plus the EC ciphers for HTTPS and JNDI used for DNS lookups)
            modules("java.instrument", "jdk.httpserver", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
            linux {
                iconFile.set(project.file("icons/nibsync.png"))
                shortcut = true
                menuGroup = "Office"
                appCategory = "Office"
                debMaintainer = "app.stemaker@gmail.com"
            }
            windows {
                iconFile.set(project.file("icons/nibsync.ico"))
                menu = true
                menuGroup = "NibSync"
                shortcut = true
                dirChooser = true
                // Keeps updates installing over the old version; never change it.
                upgradeUuid = "6f1c2a9e-3b7d-4c55-9e8a-2d4f6b1c7e30"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}

// Lets `./gradlew test` at the root include this module.
tasks.register("test") {
    dependsOn("jvmTest")
}
