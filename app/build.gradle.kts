plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kevinbuckham.minilogsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.kevinbuckham.minilogsync"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-v1"
    }

    buildTypes {
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
