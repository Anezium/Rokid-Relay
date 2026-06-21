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
        versionCode = 15
        versionName = "0.1.10-preview.5"
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
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
