import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// OAuth clients for OneDrive / Google Drive from the untracked cloud.properties (see
// cloud.properties.example); without it those services are simply not offered.
val cloudProperties = Properties().apply {
    rootProject.file("cloud.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.stemaker.nibsync.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "com.stemaker.nibsync"
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${cloudProperties.getProperty("onedrive.clientId", "")}\"")
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Nextcloud for the sync device test (the development container on this machine).
        testInstrumentationRunnerArguments["nextcloudUrl"] = providers.environmentVariable("NIBSYNC_TEST_NEXTCLOUD_URL").getOrElse("http://192.168.4.100:8089")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":core:storage"))
    implementation(project(":feature:canvas"))
    implementation(project(":feature:media"))
    implementation(project(":core:shapes"))
    implementation(project(":feature:library"))
    implementation(project(":sync:webdav"))
    implementation(project(":sync:client"))
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)

    androidTestImplementation(project(":ui:background"))
    androidTestImplementation(project(":feature:text"))
    androidTestImplementation(project(":feature:media"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okio.fakefilesystem)
}
