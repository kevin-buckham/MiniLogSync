plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build identity comes from CI (-PbuildLabel / -PbuildCode) so every APK is
// distinguishable in Downloads and inside the app itself.
val buildLabel = (project.findProperty("buildLabel") as String?) ?: "local-dev"
val buildCode = (project.findProperty("buildCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "io.github.kevinbuckham.minilogsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.kevinbuckham.minilogsync"
        minSdk = 26
        targetSdk = 34
        versionCode = buildCode
        versionName = buildLabel
    }

    // A FIXED debug key, committed to the repo. Without this every CI run
    // generates its own throwaway debug keystore, so each APK is signed with a
    // different key and Android refuses to update in place ("App not installed")
    // - which would wipe the sync history and destination on every update.
    // This is a debug key for a personal tool: it protects nothing.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
    implementation("me.jahnen.libaums:core:0.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
