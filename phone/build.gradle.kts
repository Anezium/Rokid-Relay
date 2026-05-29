plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidrelay.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidrelay.phone"
        minSdk = 31
        targetSdk = 36
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
    implementation("com.example.cxrglobal:lib:0.1.0-SNAPSHOT")
    implementation("com.rokid.cxr:client-l:1.0.1")
    implementation("androidx.core:core:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

val copyGlassesClient = tasks.register<Copy>("copyGlassesClient") {
    description = "Builds the glasses app and bundles it as a phone asset."
    dependsOn(":glasses:assembleDebug")
    from(rootProject.layout.projectDirectory.file("glasses/build/outputs/apk/debug/glasses-debug.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "rokid-relay-glasses.apk" }
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(copyGlassesClient)
}
