// tvosApp/Package.swift
// Swift Package integrating the UniFFI-generated Swift bindings
// and the Rust XCFramework into the tvOS Xcode project.

// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NodeXVPNPackage",
    platforms: [
        .iOS(.v16),
        .tvOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "NodexVpnCore",    targets: ["NodexVpnCore"]),
        .library(name: "NodexVpnCoreFFI", targets: ["NodexVpnCoreFFI"]),
    ],
    targets: [
        // ── Pre-built XCFramework (Rust static lib — built by CI) ─────────
        .binaryTarget(
            name: "NodexVpnCoreFFI",
            path: "../artifacts/NodexVpnCore.xcframework"
        ),
        // ── UniFFI-generated Swift wrapper ─────────────────────────────────
        .target(
            name: "NodexVpnCore",
            dependencies: ["NodexVpnCoreFFI"],
            path: "Sources/generated",
            publicHeadersPath: "."
        ),
    ]
)
