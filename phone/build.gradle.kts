plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jollydoddger.waymark"
    compileSdk = 35

    defaultConfig {
        // Must match :wear exactly — the Data Layer only delivers between
        // apps with the same package name and signing certificate.
        applicationId = "com.jollydoddger.waymark"
        minSdk = 26
        targetSdk = 35
        // Monotonic across CI builds, so a new APK is never mistaken for a
        // downgrade and the phone's app info says which build is installed.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        // Android refuses to install an update signed by a different key than
        // the one already on the device. Gradle's default is to generate a
        // debug key on demand, which on a fresh CI runner means a new key
        // every build — so every install after the first fails with a bare
        // "App not installed". Pinning one checked-in key fixes that.
        //
        // This is safe to commit: it signs debug builds only, it cannot
        // publish to Play, and it uses the same password Android has shipped
        // as the debug default for years.
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
        // The Anthropic SDK reaches for java.time and friends beyond minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))

    // The assistant. Same pinned version as loose-ends, whose tool-use loop
    // this app's is modelled on — its API quirks are already paid for.
    implementation("com.anthropic:anthropic-java:2.10.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
