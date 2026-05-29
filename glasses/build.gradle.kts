plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidrelay.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidrelay.glasses"
        minSdk = 31
        targetSdk = 32
        versionCode = 5
        versionName = "0.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260212.103714-88")
}
