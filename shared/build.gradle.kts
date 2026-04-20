// shared/build.gradle.kts
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    // CocoaPods integration — exposes pod() deps to iosMain source sets
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    // ── Android ───────────────────────────────────────────────────────────────
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ── iOS ───────────────────────────────────────────────────────────────────
    // Targets declared here; framework config lives in cocoapods{} below
    iosX64(); iosArm64(); iosSimulatorArm64()

    // ── Desktop (JVM) ─────────────────────────────────────────────────────────
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ── Source Sets ───────────────────────────────────────────────────────────
    sourceSets {

        // ── Common ────────────────────────────────────────────────────────────
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                // Resources — needed for Compose Multiplatform resource system
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                // Ktor
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)

                // Koin DI
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
            }
        }

        // ── Android ───────────────────────────────────────────────────────────
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.koin.android)
                // Firebase + GMS — needed by AuthRepository.android.kt
                // NOTE: platform() inside KMP sourceSets does NOT accept Provider<MinimalExternalModuleDependency>
                // (i.e. libs.firebase.bom directly). Passing a Provider causes:
                //   "Cannot convert map(valueof(DependencyValueSource)) to type Dependency"
                // Fix: resolve version eagerly via libs.versions.firebaseBom.get() and pass a String.
                implementation(platform("com.google.firebase:firebase-bom:${libs.versions.firebaseBom.get()}"))
                implementation(libs.firebase.auth)
                implementation(libs.google.play.auth)
                implementation(libs.kotlinx.coroutines.play)
            }
        }

        // ── iOS ───────────────────────────────────────────────────────────────
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosX64Main            by getting { dependsOn(iosMain) }
        val iosArm64Main          by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        // ── Desktop ───────────────────────────────────────────────────────────
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                // JNA — needed by PlatformVpnBridge.desktop.kt to load the Rust native library
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
            }
        }
    }

    // ── CocoaPods ─────────────────────────────────────────────────────────────
    // IMPORTANT: cocoapods{} is an extension on KotlinMultiplatformExtension,
    // so it must live INSIDE kotlin{}, not at the top level.
    cocoapods {
        summary  = "NodeX VPN shared KMP library"
        homepage = "https://github.com/AeonCoreX/NodeX-VPN"
        version  = "1.0"
        ios.deploymentTarget = "16.0"
        pod("FirebaseAuth") { version = "~> 11.6" }
        pod("GoogleSignIn") { version = "~> 8.0"  }
        framework {
            baseName  = "shared"
            isStatic  = true
            linkerOpts += listOf(
                "-F${project.rootDir}/rust-core/target/universal-ios",
                "-framework", "nodex_vpn_core",
            )
        }
    }
}

// ── Compose Resources configuration ──────────────────────────────────────────
compose.resources {
    publicResClass    = true
    packageOfResClass = "com.nodex.vpn.shared"
    generateResClass  = always
}

android {
    namespace         = "com.nodex.vpn.shared"
    compileSdk        = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ── Rust JNI/NDK linking task ─────────────────────────────────────────────────
tasks.register("copyRustLibs") {
    val targets = mapOf(
        "aarch64-linux-android"   to "arm64-v8a",
        "armv7-linux-androideabi" to "armeabi-v7a",
        "x86_64-linux-android"   to "x86_64",
        "i686-linux-android"     to "x86",
    )
    doLast {
        targets.forEach { (triple, abi) ->
            val src = rootDir.resolve("rust-core/target/$triple/release/libnodex_vpn_core.so")
            val dst = projectDir.resolve("src/androidMain/jniLibs/$abi")
            if (src.exists()) {
                dst.mkdirs()
                src.copyTo(dst.resolve("libnodex_vpn_core.so"), overwrite = true)
            }
        }
    }
}
tasks.matching { it.name.startsWith("pre") && it.name.contains("Build") }
    .configureEach { dependsOn("copyRustLibs") }
