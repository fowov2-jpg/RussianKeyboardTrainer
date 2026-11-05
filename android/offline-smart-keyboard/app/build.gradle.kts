plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.offline.smartkeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.offline.smartkeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.11.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Verify touch-proximity correction on real user typo examples.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
