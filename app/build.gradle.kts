plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nessuna libreria da Maven: tutto è incluso nel progetto.
//  - libs/jna-5.12.1.jar                   (JNA, chiamate alle librerie C)
//  - src/main/jniLibs/arm64-v8a/*.so       (LiteRT-LM 0.18.0 da PyPI "litert-lm-api",
//                                            Vosk 0.3.45 dalla release GitHub alphacep,
//                                            libjnidispatch di JNA 5.12.1)

android {
    namespace = "com.essentialapp.sam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.essentialapp.sam"
        minSdk = 31
        targetSdk = 35
        versionCode = 43
        versionName = "2.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // La chiave di firma resta sul PC: senza il file (es. build su GitHub) l'APK esce non firmato
    // e si firma dopo, in locale, con la stessa chiave.
    signingConfigs {
        if (file("sam-release.jks").exists()) create("release") {
            storeFile = file("sam-release.jks")
            storePassword = "samsam2026"
            keyAlias = "sam"
            keyPassword = "samsam2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    packaging {
        // librerie native estratte all'installazione (come nella build manuale)
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(files("libs/jna-5.12.1.jar"))
}
