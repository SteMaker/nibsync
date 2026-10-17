// Adapter between the platform-neutral domain model and androidx.ink. All Ink types stay
// inside this module so the rest of the app never depends on them directly.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "de.nibsync.ink"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.ink.authoring)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.geometry)
    api(libs.androidx.ink.rendering)
    api(libs.androidx.ink.strokes)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.truth)
}
