plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Phone companion for the TV photo frame (../photoframe): share-sheet uploads, finds the TV
// on the Wi-Fi by itself, and wraps the TV's web app.
android {
    namespace = "com.jollydoddger.photoframe.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jollydoddger.photoframe.phone"
        minSdk = 28
        // 35 forces edge-to-edge, which would put the web app under the status bar.
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        // Same pinned key as the rest of the repo, so updates install over each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
