plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.viktor.ambiance"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.viktor.ambiance"
        minSdk = 26
        // 34 : pas d'affichage bord à bord forcé, la télécommande garde des barres système sombres
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"

        // Une seule APK pour les deux appareils :
        // Pixel 10 = arm64, Mi Box S 2e gén. = système Android TV souvent en 32 bits
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clé fixe versionnée : chaque nouvelle build s'installe par-dessus la précédente
    signingConfigs {
        create("perso") {
            storeFile = file("ambiance.keystore")
            storePassword = "ambiance"
            keyAlias = "ambiance"
            keyPassword = "ambiance"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("perso")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Obligatoire pour youtubedl-android : Python et yt-dlp sont livrés
    // comme bibliothèques natives à extraire sur le disque.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

val youtubedlAndroid = "0.18.1"
val media3 = "1.5.1"

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
