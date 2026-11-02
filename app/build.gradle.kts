import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is opt-in and guarded: present only when a keystore.properties
// (gitignored) exists with storeFile/storePassword/keyAlias/keyPassword. CI and
// cloner machines without it still produce unsigned release builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.fusioncam.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.fusioncam.scanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.6.0"

        ndk {
            // arm64-v8a: modern phones; armeabi-v7a: 32-bit Android 8/9 era
            // devices; x86_64: emulators.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
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
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
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

// Intentionally dependency-free: plain Activity + platform views.
// Phase 1 (capture skeleton) will introduce CameraX when the capability
// matrix exists and we know which devices we support.
dependencies {
}
