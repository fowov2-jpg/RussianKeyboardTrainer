plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.russiankeyboard.prototype"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    // Avoid shipping three CPU architectures inside one large APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "dev.russiankeyboard.prototype"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.0-rc8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // AssetFileDescriptor + mmap require an uncompressed APK entry.
        noCompress += "bin"
    }
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("test").resources.srcDir("src/sharedTest/fixtures")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
        getByName("androidTest").assets.srcDir("src/sharedTest/fixtures")
    }
    testOptions {
        unitTests.all {
            it.systemProperty("rsk.lexicon", file("src/main/assets/lexicon/russian.bin").absolutePath)
            it.systemProperty("rsk.lexiconReport", layout.buildDirectory.file("reports/lexicon-runtime.json").get().asFile.absolutePath)
            it.inputs.file(file("src/main/assets/lexicon/russian.bin"))
                .withPropertyName("russianLexicon")
            it.systemProperty("rsk.context", file("src/main/assets/context").absolutePath)
            it.systemProperty("rsk.common", file("src/main/assets/common").absolutePath)
            it.systemProperty("rsk.contextReport", layout.buildDirectory.file("reports/context-runtime.json").get().asFile.absolutePath)
            it.inputs.dir(file("src/main/assets/context")).withPropertyName("contextAssets")
            it.systemProperty("rsk.punctuation", file("src/main/assets/punctuation").absolutePath)
            it.systemProperty("rsk.extendedReport", layout.buildDirectory.dir("reports/extended-qa").get().asFile.absolutePath)
            it.inputs.dir(file("src/main/assets/punctuation")).withPropertyName("punctuationAssets")
            it.outputs.file(layout.buildDirectory.file("reports/lexicon-runtime.json"))
            it.outputs.file(layout.buildDirectory.file("reports/context-runtime.json"))
        }
    }
}

dependencies {
    // Local neural punctuation; one session on the bounded analysis worker.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
