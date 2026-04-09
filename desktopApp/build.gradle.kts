import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // JNA for Rust native library loading
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nodex.vpn.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,    // macOS
                TargetFormat.Msi,    // Windows
                TargetFormat.Deb,    // Debian/Ubuntu
                TargetFormat.Rpm,    // Fedora/RHEL
            )

            packageName    = "NodeX VPN"
            packageVersion = "0.1.0"
            vendor         = "NodeX Project"
            description    = "Serverless VPN powered by the Tor network"
            copyright      = "© 2026 AeonCoreX"
            licenseFile    = rootProject.file("LICENSE")

            // ── macOS ──────────────────────────────────────────────────────────
            macOS {
                bundleID          = "com.nodex.vpn"
                appStore          = false
                dmgPackageVersion = "0.3.0"
                // FIX: project.file() returns java.io.File but iconFile and
                // entitlementsFile expect RegularFileProperty (Gradle Provider API).
                // Use layout.projectDirectory.file() which returns the correct type.
                iconFile          = project.layout.projectDirectory.file("resources/macos/AppIcon.iconset/icon_512x512@2x.png")
                // Embed the Rust dylib
                jvmArgs += listOf("-Djava.library.path=Contents/MacOS")
                entitlementsFile = project.layout.projectDirectory.file("macos/entitlements.plist")
                // Include the Rust dylib in the bundle
                appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            }

            // ── Windows ────────────────────────────────────────────────────────
            windows {
                msiPackageVersion   = "0.1.0"
                upgradeUuid         = "3A4B5C6D-7E8F-9A0B-C1D2-E3F4A5B6C7D8"
                menuGroup           = "NodeX VPN"
                shortcut            = true
                dirChooser          = true
                perUserInstall      = false  // needs admin for TUN driver
                // FIX: same as macOS — use layout.projectDirectory.file()
                iconFile            = project.layout.projectDirectory.file("resources/windows/nodex.ico")
            }

            // ── Linux ──────────────────────────────────────────────────────────
            linux {
                debMaintainer       = "contact@nodex.vpn"
                menuGroup           = "Network"
                appCategory         = "Network"
                // FIX: same as macOS — use layout.projectDirectory.file()
                iconFile            = project.layout.projectDirectory.file("resources/linux/nodex.png")
                // Post-install script to set CAP_NET_ADMIN
                debPackageVersion   = "0.1.0"
            }

            // ── JVM args for all platforms ─────────────────────────────────────
            jvmArgs += listOf(
                "-Xmx256m",
                "-Xms64m",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
            )

            // ── Modules ───────────────────────────────────────────────────────
            modules(
                "java.net.http",
                "jdk.crypto.cryptoki",
                "jdk.crypto.ec",
            )
        }
    }
}

// ── Task: copy Rust native libs into resources before packaging ───────────────
tasks.register("copyDesktopRustLibs") {
    val libsMap = mapOf(
        "x86_64-unknown-linux-gnu"  to ("linux"   to "libnodex_vpn_core.so"),
        "x86_64-apple-darwin"       to ("macos"   to "libnodex_vpn_core.dylib"),
        "aarch64-apple-darwin"      to ("macos"   to "libnodex_vpn_core.dylib"),
        "x86_64-pc-windows-msvc"    to ("windows" to "nodex_vpn_core.dll"),
    )
    doLast {
        libsMap.forEach { (triple, pair) ->
            val (platform, libFile) = pair
            val src = rootDir.resolve("rust-core/target/$triple/release/$libFile")
            val dst = projectDir.resolve("resources/$platform")
            if (src.exists()) {
                dst.mkdirs()
                src.copyTo(dst.resolve(libFile), overwrite = true)
                println("Copied $libFile → $dst")
            }
        }
        // Also copy wintun.dll for Windows
        val wintun = rootDir.resolve("rust-core/vendor/wintun/wintun.dll")
        if (wintun.exists()) {
            val dst = projectDir.resolve("resources/windows")
            dst.mkdirs()
            wintun.copyTo(dst.resolve("wintun.dll"), overwrite = true)
        }
    }
}
tasks.named("packageDmg")  { dependsOn("copyDesktopRustLibs") }
tasks.named("packageMsi")  { dependsOn("copyDesktopRustLibs") }
tasks.named("packageDeb")  { dependsOn("copyDesktopRustLibs") }
tasks.named("packageRpm")  { dependsOn("copyDesktopRustLibs") }
