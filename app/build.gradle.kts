plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.localeai"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "it.localeai"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // llama.cpp compilato SEMPRE ottimizzato, anche nell'APK debug:
                    // in Debug (-O0) l'inferenza sarebbe 10-50 volte più lenta.
                    "-DCMAKE_BUILD_TYPE=Release",
                    // Compatibilità con i dispositivi Android 15+ a pagine da 16 KB.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    // Chiave fissa: gli aggiornamenti si installano sopra la versione
    // precedente senza disinstallare. Solo per uso personale.
    signingConfigs {
        create("localeai") {
            storeFile = file("localeai.keystore")
            storePassword = "localeai2026"
            keyAlias = "localeai"
            keyPassword = "localeai2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("localeai")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localeai")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
