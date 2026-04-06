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
}

kotlin {
    // ── Android ───────────────────────────────────────────────────────────────
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ── iOS ───────────────────────────────────────────────────────────────────
    listOf(
        iosX64(), iosArm64(), iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName   = "shared"
            isStatic   = true
            linkerOpts += listOf(
                "-F${project.rootDir}/rust-core/target/universal-ios",
                "-framework", "nodex_vpn_core",
            )
        }
    }

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

                // ✅ Resources — needed for painterResource(Res.drawable.xxx)
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
            }
        }
    }
}

// ── Compose Resources configuration ──────────────────────────────────────────
// This block configures the multiplatform resource system so that
// shared/src/commonMain/composeResources/drawable/ic_nodex_logo.png
// is accessible in all platforms via Res.drawable.ic_nodex_logo
compose.resources {
    publicResClass    = true          // Generates a public Res object
    packageOfResClass = "com.nodex.vpn.shared"
    generateResClass  = always        // Always regenerate on sync
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
