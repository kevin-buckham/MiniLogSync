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

    // Release builds are signed with a key that lives ONLY in GitHub Actions
    // secrets (never in this repo). CI decodes it to a temp file and passes the
    // path and passwords through the environment. Every CI build is signed with
    // the same key, so Android updates in place and keeps the sync history.
    // Without those variables (a fork, a local build) the release APK is left
    // unsigned; use assembleDebug locally instead.
    val releaseKeystore = System.getenv("MLS_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MLS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MLS_KEY_ALIAS")
                keyPassword = System.getenv("MLS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
