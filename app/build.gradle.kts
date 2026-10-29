plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.fusioncam.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.fusioncam.scanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase0"
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

// Intentionally dependency-free: plain Activity + platform views.
// Phase 1 (capture skeleton) will introduce CameraX when the capability
// matrix exists and we know which devices we support.
dependencies {
}
