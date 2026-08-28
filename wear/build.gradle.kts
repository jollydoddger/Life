plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jollydoddger.waymark"
    compileSdk = 35

    defaultConfig {
        // Must match :phone exactly — the Data Layer only delivers between
        // apps with the same package name and signing certificate.
        applicationId = "com.jollydoddger.waymark"
        minSdk = 30 // Wear OS 3, the Galaxy Watch 5 Pro's floor
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        // Same committed key as :phone — see phone/build.gradle.kts for why,
        // plus the Data Layer's same-signature requirement above.
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
    implementation(project(":shared"))
}
