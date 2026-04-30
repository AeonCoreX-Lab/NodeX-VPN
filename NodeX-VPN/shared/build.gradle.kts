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
    // CocoaPods integration — exposes pod() deps to iosMain + tvosMain source sets
    id("org.jetbrains.kotlin.native.cocoapods")
}

// ── BuildConfig for Desktop (Google OAuth2 + Firebase credentials) ────────────
val googleClientId     = System.getenv("NODEX_DESKTOP_GOOGLE_CLIENT_ID")     ?: project.findProperty("nodex.google.client.id")?.toString()     ?: "PLACEHOLDER"
val googleClientSecret = System.getenv("NODEX_DESKTOP_GOOGLE_CLIENT_SECRET") ?: project.findProperty("nodex.google.client.secret")?.toString() ?: "PLACEHOLDER"
val firebaseApiKey     = System.getenv("FIREBASE_WEB_API_KEY")               ?: project.findProperty("nodex.firebase.api.key")?.toString()      ?: "PLACEHOLDER"

kotlin {
    // ── Android ───────────────────────────────────────────────────────────────
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ── iOS ───────────────────────────────────────────────────────────────────
    iosX64(); iosArm64(); iosSimulatorArm64()

    // ── tvOS ──────────────────────────────────────────────────────────────────
    tvosX64(); tvosArm64(); tvosSimulatorArm64()

    // ── Desktop (JVM) ─────────────────────────────────────────────────────────
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ── Source Sets ───────────────────────────────────────────────────────────
    // Generate BuildConfig.kt for desktop
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig/desktopMain/kotlin")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        File(dir, "BuildConfig.kt").writeText("""
package com.nodex.vpn.auth

internal object BuildConfig {
    const val GOOGLE_CLIENT_ID:     String = "$googleClientId"
    const val GOOGLE_CLIENT_SECRET: String = "$googleClientSecret"
    const val FIREBASE_API_KEY:     String = "$firebaseApiKey"
}
""".trimIndent())
    }
}

kotlin.sourceSets.getByName("desktopMain").kotlin.srcDir(
    layout.buildDirectory.dir("generated/buildconfig/desktopMain/kotlin")
)

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuildConfig)
}

sourceSets {

        // ── Common ────────────────────────────────────────────────────────────
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)

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

        // ── Apple shared (iOS + tvOS share Darwin/Foundation/Ktor) ────────────
        val appleMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Darwin Ktor engine used by both iOS and tvOS
                implementation(libs.ktor.client.darwin)
            }
        }

        // ── iOS ───────────────────────────────────────────────────────────────
        val iosMain by creating {
            dependsOn(appleMain)
            // UIKit + NetworkExtension available via KN platform interop (no extra deps)
        }
        val iosX64Main            by getting { dependsOn(iosMain) }
        val iosArm64Main          by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        // ── tvOS ──────────────────────────────────────────────────────────────
        // tvOS shares Apple auth (FirebaseAuth via CocoaPods) but has NO NetworkExtension.
        // VPN tunneling is unavailable on tvOS — the TV app acts as a remote control
        // for a paired iPhone/Mac running NodeX VPN.
        val tvosMain by creating {
            dependsOn(appleMain)
        }
        val tvosX64Main            by getting { dependsOn(tvosMain) }
        val tvosArm64Main          by getting { dependsOn(tvosMain) }
        val tvosSimulatorArm64Main by getting { dependsOn(tvosMain) }

        // ── Desktop ───────────────────────────────────────────────────────────
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
            }
        }
    }

    // ── CocoaPods ─────────────────────────────────────────────────────────────
    // cocoapods{} is an extension on KotlinMultiplatformExtension — must be INSIDE kotlin{}.
    cocoapods {
        summary  = "NodeX VPN shared KMP library"
        homepage = "https://github.com/AeonCoreX/NodeX-VPN"
        version  = "1.0"
        ios.deploymentTarget  = "16.0"
        tvos.deploymentTarget = "16.0"
        pod("FirebaseAuth") {
            version    = "~> 11.6"
            // FIX: Xcode 16.4 + iOS SDK 18.5 cinterop fails with:
            //   c_standard_library.modulemap:313: module '_stddef' requires
            //   feature 'found_incompatible_headers__check_search_paths'
            // Root cause: KN cinterop passes -fmodules which triggers a clang
            // modulemap header-search-path validation bug in SDK 18.5.
            // Fix: disable module mode so cinterop uses plain -I includes.
            extraOpts = listOf("-compiler-option", "-fno-modules")
        }
        pod("GoogleSignIn") {
            version   = "~> 8.0"
            extraOpts = listOf("-compiler-option", "-fno-modules")
        }
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

// ── Compose Resources ─────────────────────────────────────────────────────────
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

// ── Rust JNI copy task (local development) ────────────────────────────────────
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
