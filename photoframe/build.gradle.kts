plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jollydoddger.photoframe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jollydoddger.photoframe"
        // 28 = ImageDecoder (HEIC + EXIF rotation built in).
        minSdk = 28
        // Kept at 34: 35 forces edge-to-edge, which buys nothing on a TV.
        targetSdk = 34
        // Monotonic across CI builds, so `adb install -r` never sees a downgrade.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        // Same pinned key as Waymark: a fresh CI runner would otherwise mint a
        // new debug key per build and every update would fail to install.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // FrameState makes a main-thread Handler; on the JVM that is a harmless stub.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
