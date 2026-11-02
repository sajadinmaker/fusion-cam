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
        versionCode = 2
        versionName = "0.6.0-yuv-fallback"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.3.11579264"

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
