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
        versionCode = 33
        versionName = "0.1.15-preview.18"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    implementation("com.example.cxrglobal:lib:0.2.0")
    implementation("androidx.core:core:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
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
