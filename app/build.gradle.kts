plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rpax.tpms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rpax.tpms"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Wear OS messaging
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Android Auto
    implementation("androidx.car.app:app:1.4.0")
}
