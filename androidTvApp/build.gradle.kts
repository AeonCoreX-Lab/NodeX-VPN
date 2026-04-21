// androidTvApp/build.gradle.kts
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("com.google.gms.google-services")
}

kotlin {
    androidTarget()
    sourceSets {
        val androidMain by getting {
            dependencies { implementation(project(":shared")) }
        }
    }
}

android {
    namespace = "com.nodex.vpn.androidtv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nodex.vpn.androidtv"
        minSdk        = 26       // Android TV minimum (Leanback LGTV/Bravia support)
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
    }

    signingConfigs {
        create("release") {
            storeFile    = file("../androidApp/nodex-release.jks")
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias     = System.getenv("KEY_ALIAS")
            keyPassword  = System.getenv("KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../androidApp/proguard-rules.pro",
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    // Compose for TV — D-pad navigation, focus management, TV Material3
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    // Firebase (same google-services.json as androidApp)
    implementation(platform("com.google.firebase:firebase-bom:${libs.versions.firebaseBom.get()}"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.android.gms:play-services-auth:${libs.versions.googlePlayServicesAuth.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${libs.versions.coroutines.get()}")
}
