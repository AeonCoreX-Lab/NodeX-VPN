<div align="center">

<img src="desktopApp/resources/macos/AppIcon.iconset/icon_512x512.png" width="120" height="120" alt="NodeX VPN Logo" />

# NodeX VPN

### Serverless · Anonymous · Tor-Powered

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Rust](https://img.shields.io/badge/Rust-stable-CE422B?style=flat-square&logo=rust)](https://rustlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_MP-1.7.3-4285F4?style=flat-square&logo=jetpackcompose)](https://www.jetbrains.com/compose-multiplatform/)
[![Tor](https://img.shields.io/badge/Powered_by-Tor_Network-7E4798?style=flat-square)](https://torproject.org)
[![License](https://img.shields.io/badge/License-MIT-00F5FF?style=flat-square)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android_·_iOS_·_TV_·_macOS_·_Windows_·_Linux_·_Router_·_NAS-green?style=flat-square)](#-platform-support)

**A production-ready, serverless VPN built on the Tor network.**  
**Zero owned servers · Zero logs · 99% IP anonymity · Rust-powered speed**  
**11 platforms — Android, iOS, tvOS, Android TV, macOS, Windows, Linux, OpenWrt/GL.iNet, Asus Merlin, pfSense/OPNsense, Synology NAS**

[Features](#-features) · [Platforms](#-platform-support) · [Architecture](#️-architecture) · [Getting Started](#-getting-started) · [Build](#️-build) · [CI/CD](#-cicd) · [FAQ](#-faq)

---

</div>

## 🌟 What Makes NodeX VPN Different

Most VPNs route your traffic through **servers they own** — meaning they *could* log you. NodeX VPN has **no servers**. Your traffic routes through the **Tor network** — thousands of volunteer-operated relays worldwide — making surveillance structurally impossible.

```
Your Device  →  Guard Relay  →  Middle Relay  →  Exit Relay  →  Internet
              (knows you,        (knows nothing    (knows dest,
               not dest)          about you)        not you)
```

> **No server to hack. No logs to subpoena. No company to trust.**

---

## ✨ Features

### 🔒 Security & Privacy
| Feature | Details |
|---------|---------|
| **Serverless Architecture** | Routes through Tor — no owned infrastructure |
| **99% IP Anonymity** | Strict exit node enforcement via `ExitNodes` |
| **obfs4 Bridge Support** | Bypasses ISP deep packet inspection & government firewalls |
| **DNS-over-Tor** | All DNS queries routed through Tor — zero DNS leaks |
| **Kill Switch** | Blocks all traffic if VPN connection drops |
| **Zero Logs** | No user data ever stored or transmitted |

### 🌍 Connectivity
| Feature | Details |
|---------|---------|
| **18 Exit Countries** | US, DE, NL, JP, GB, SG, CA, FR, CH, AU, SE, NO, IS, RO, UA, ZA, BR, IN |
| **Live Country Switching** | Change exit country without reconnecting |
| **Real-time Latency** | Live latency measurement per server node |
| **Bridge Management** | Add/remove obfs4 bridges at runtime |

### ⚡ Performance
| Feature | Details |
|---------|---------|
| **Rust Core Engine** | Memory-safe, zero-overhead networking via `arti-client` |
| **Async I/O** | Tokio-powered async runtime — handles 1M+ concurrent users |
| **Live Traffic Graph** | Real-time bandwidth visualization |
| **Circuit Management** | Multi-circuit Tor connection pooling |

### 📺 TV & Router (New)
| Feature | Details |
|---------|---------|
| **Android TV** | D-pad / remote optimized Leanback UI, TV launcher integration |
| **Apple TV (tvOS)** | Full KMP shared UI, `NEAppProxyProvider` VPN tunnel, wide-format icons |
| **Router Mode — OpenWrt** | `procd` service + `iptables` transparent proxy via TUN |
| **Router Mode — GL.iNet** | Plug-and-play, runs on existing OpenWrt base |
| **Router Mode — Asus Merlin** | JFFS2 persistent scripts, auto-start on boot |
| **Whole-Home Protection** | Smart TV, PS5, Xbox, IoT devices protected — no app needed |

---

## 📱 Platform Support

### ✅ Supported Now (11 Platforms)

| Platform | Min Version | Tunnel Method | App Module |
|----------|------------|---------------|------------|
| **Android Phone / Tablet** | API 26 (Android 8.0+) | `VpnService` + JNI tun2socks | `androidApp/` |
| **Android TV** | API 26 + Leanback | `VpnService` + JNI tun2socks | `androidTvApp/` |
| **iOS (iPhone / iPad)** | iOS 16+ | `NetworkExtension` PacketTunnelProvider | `iosApp/` |
| **Apple TV (tvOS)** | tvOS 16+ | `NetworkExtension` AppProxyProvider | `tvosApp/` |
| **macOS** | macOS 13+ | `utun` + pfctl routing | `desktopApp/` |
| **Linux Desktop** | Ubuntu 20.04+ / Fedora 36+ | `/dev/net/tun` + iptables | `desktopApp/` |
| **Windows** | Windows 10 x64+ | Wintun driver | `desktopApp/` |
| **Router — OpenWrt / GL.iNet** | Linux 4.9+, 64-bit | TUN + iptables transparent proxy | `router/` |
| **Router — Asus Merlin** | aarch64 / x86_64 | TUN + iptables (JFFS2 persistent) | `router/` |
| **Router — pfSense / OPNsense** | pfSense 2.7+ / OPNsense 24+ (FreeBSD 14) | `/dev/tun0` + `pf(4)` rdr-anchor | `router/` |
| **NAS — Synology DSM** | DSM 6.2+ or 7.x · x86\_64 / aarch64 | `/dev/net/tun` + iptables · SPK package | `router/` |

### 🔜 Planned Support

| Platform | What's Needed | Priority |
|----------|--------------|----------|
| **DD-WRT / Tomato** | Same Linux binary, manual init.d (basic support already in `install.sh`) | Low |
| **Amazon Fire TV** | Android TV APK works via sideload; Play Store listing needed | Medium |
| **Ubiquiti EdgeOS / UniFi** | EdgeOS is Debian-based — Linux binary works; needs `setup_unifi.sh` | Medium |
| **watchOS** | Limited NE API on watchOS; depends on Apple expanding VPN access | Low |
| **Windows ARM64 Desktop** | Compose Multiplatform desktop doesn't yet publish `windows-arm64`; Rust binary already built | Waiting on JB |

---

## 📺 Android TV — Details

The `androidTvApp/` module is a purpose-built Android TV app:

- **Leanback launcher** — appears on the TV home row, not just in all-apps
- **D-pad navigation** — every button, server row, and settings option navigable via remote
- **TV banner** — `320×180dp` vector banner (`tv_banner.xml`) for launcher display
- **Shared JNI** — same `libnodex_vpn_core.so` from the phone app; no separate Rust build
- **Separate AAB** — distinct Google Play track (Android TV / Google TV)

```bash
./gradlew :androidTvApp:assembleDebug    # TV APK
./gradlew :androidTvApp:bundleRelease    # TV AAB for Play Store
```

---

## 📺 Apple TV (tvOS) — Details

The `tvosApp/` is a full Xcode project parallel to `iosApp/`:

- **`AppProxyProvider`** — tvOS 16+ supports `NEAppProxyProvider`; used instead of `PacketTunnelProvider` (which is iOS/macOS only)
- **Wide-format icons** — tvOS requires banner icons, not square ones:
  - `AppIcon-400x240.png` (1x)
  - `AppIcon-800x480.png` (2x)
  - `AppIcon-1280x768.png` (TV marketing)
- **KMP shared UI** — `TvMainViewController.kt` wires the shared `TvApp` composable into SwiftUI via `UIViewControllerRepresentable`
- **Firebase Auth** — Google Sign-In on tvOS uses device-flow OAuth (QR code / code-on-phone)
- **Separate CI job** — `tvos` job builds `NodeXTvVPN.xcworkspace`, exports `.ipa`

```bash
# Open in Xcode
open tvosApp/NodeXTvVPN.xcworkspace
# Or via CI
xcodebuild archive -workspace tvosApp/NodeXTvVPN.xcworkspace -scheme NodeXTvVPN -sdk appletvos
```

---

## 🏠 Router / NAS Mode — Details

The `router/` directory is a complete multi-platform deployment system:

```
router/
├── install.sh                   # Universal: auto-detects all 5 router/NAS platforms
├── setup_openwrt.sh             # OpenWrt: kmod-tun, iptables, UCI zone, hotplug hook
├── setup_glinet_merlin.sh       # GL.iNet / Asus Merlin: JFFS2 paths
├── setup_pfsense_opnsense.sh    # pfSense / OPNsense: pf(4) anchor, FreeBSD rc.d, configd
├── setup_synology.sh            # Synology DSM 6/7: SPK, rc.d/Upstart, 3 routing modes
├── package.sh                   # CI: .tar.gz (all targets) + .spk (Synology)
└── README.md                    # Full documentation
```

**How it works:**
```
LAN Device → iptables PREROUTING (br-lan)
              ├── TCP  → NodeX TransPort :9040 → Tor circuit → Internet
              └── DNS  → NodeX DNSPort   :5353 → DNS-over-Tor → resolved
```

**Quick install on OpenWrt:**
```sh
ssh root@192.168.1.1
opkg install kmod-tun
wget -O- https://github.com/your-org/NodeX-VPN/releases/latest/download/install.sh | sh
sh /tmp/setup_openwrt.sh
/etc/init.d/nodex start
```

**Supported hardware (64-bit only):**
- GL.iNet GL-MT6000, GL-AXT1800, GL-MT3000
- Asus RT-AX88U, RT-AX86U (Merlin firmware)
- Linksys WRT3200ACM, WRT1900ACS (OpenWrt)
- Any x86_64 mini-PC running OpenWrt (e.g. Topton, Beelink)
- **pfSense / OPNsense** — any x86_64 PC/appliance (Netgate SG-1100 not supported — armv7)
- **Synology NAS** — DS920+, DS923+, DS1621+, DS220+, DS420+, DS720+ and most 2019+ models

> ⚠️ **UDP (except DNS) is not anonymized** — fundamental Tor limitation. PS5/Xbox game traffic over UDP uses real IP. TCP connections are fully protected.

---

## 🔥 pfSense / OPNsense — Details

NodeX VPN runs as a native **FreeBSD** process — built with `x86_64-unknown-freebsd` Rust target, no Linux emulation.

**Tunnel mechanism:**
```
LAN client → pf(4) rdr-anchor "nodex"
              ├── TCP  → rdr to 127.0.0.1:9040 (NodeX TransPort → Tor)
              └── DNS  → rdr to 127.0.0.1:5353 (NodeX DNSPort → DNS-over-Tor)
                           ↓
              /dev/tun0 (tun0 — 10.66.0.1 ↔ 10.66.0.2)
                           ↓
              arti Tor SOCKS5 → Tor Network → Internet
```

**Key implementation details:**
- Rust `tunnel/bsd.rs` — FreeBSD TUN, no 4-byte PI header (auto-detects BSD AF header)
- pf anchor file: `/etc/nodex/nodex.pf` — loaded via `pfctl -a nodex -f ...`
- **pfSense**: filter hook at `/etc/rc.filter_configure_sync.d/10_nodex_anchor.sh` re-applies anchor after every firewall reload
- **OPNsense**: `configd` action in `/usr/local/opnsense/service/conf/actions.d/` re-applies anchor after rule changes
- Kernel module: `if_tun` loaded at startup + persisted in `/boot/loader.conf`
- Does **not** replace your existing pf ruleset — operates as an isolated `rdr-anchor`

```sh
# Quick install
fetch -o /tmp/s.sh https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_pfsense_opnsense.sh
sh /tmp/s.sh [--lan em1] [--lan-net 192.168.1.0/24]
service nodex_vpn start
```

---

## 🖥️ Synology NAS — Details

Synology DSM is Linux-based — the standard `aarch64-unknown-linux-gnu` / `x86_64-unknown-linux-gnu` binaries work directly.

**Three routing modes:**

| Mode | What gets protected |
|------|---------------------|
| `--both` (default) | NAS outbound traffic + all LAN clients |
| `--nas-only` | Only NAS itself (downloads, Docker containers, etc.) |
| `--lan-only` | Only LAN clients (NAS as transparent Tor gateway) |

**Installation options:**

| Method | How |
|--------|-----|
| SSH installer | `curl ... \| sh` — fastest, full control |
| SPK package | Download `.spk` → Package Center → Manual Install |
| CI artifact | Built by `package.sh`, published to GitHub Releases |

**Supported DSM:**
- DSM 7.x: `/usr/local/etc/rc.d/nodex-vpn.sh` (synoinit-compatible)
- DSM 6.2: `/etc/init/nodex-vpn.conf` (Upstart)

**Supported NAS models (64-bit only):**
- x86_64: DS920+, DS923+, DS1621+, DS1821+, DS1522+, RS1221+
- aarch64: DS220+, DS420+, DS720+, DS920+ (RTD1619B / Cortex-A55)

```sh
# SSH install
sudo -i
curl -fsSL https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_synology.sh | sh

# Or: SPK from Package Center
# Download NodeX-VPN-<ver>-x86_64.spk from Releases → Package Center → Manual Install
```

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION LAYER                               │
│            Compose Multiplatform (Kotlin) — shared UI                    │
│  ┌──────┐ ┌──────────┐ ┌──────┐ ┌─────────┐ ┌────────┐ ┌────────────┐  │
│  │Splash│ │Onboarding│ │ Auth │ │Dashboard│ │Settings│ │  TV Suite  │  │
│  └──────┘ └──────────┘ └──────┘ └─────────┘ └────────┘ └────────────┘  │
├──────────────────────────────────────────────────────────────────────────┤
│                      SHARED LOGIC LAYER (KMP)                            │
│   VpnManager · AuthViewModel · AuthRepository · Koin DI                 │
│   expect/actual: PlatformVpnBridge · AuthRepository · WindowSize        │
├────────┬────────┬──────────┬──────────┬──────────┬──────────┬───────────┤
│Android │Android │   iOS    │  tvOS    │  Linux/  │  macOS   │  Windows  │
│ Phone  │   TV   │  Phone   │Apple TV  │  Linux   │  macOS   │  Windows  │
│VpnSvc  │VpnSvc  │ NetExt   │AppProxy  │ /dev/tun │   utun   │  Wintun   │
│JNI/NDK │JNI/NDK │XCFrmwk   │XCFrmwk   │ iptables │  pfctl   │  WinAPI   │
├────────┴────────┴──────────┴──────────┴──────────┴──────────┴───────────┤
│              ROUTER / NAS LAYER (new)                                    │
│  OpenWrt/GL.iNet · Asus Merlin · pfSense/OPNsense · Synology DSM        │
│  Linux: iptables PREROUTING + TUN    BSD: pf(4) rdr-anchor + /dev/tun0  │
├────────┴────────┴──────────┴──────────┴──────────┴──────────┴───────────┤
│                         RUST CORE ENGINE                                 │
│   arti-client (Tor) · SOCKS5 Proxy · tun2socks · DNS · Stats           │
│   UniFFI → auto-generates Kotlin + Swift + C bindings                   │
└──────────────────────────────────────────────────────────────────────────┘
                                │
                      Tor Network (Distributed)
                    Guard → Middle → Exit → Internet
```

### Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **UI** | Compose Multiplatform 1.7.3 | Unified UI — all 9 platforms |
| **Shared Logic** | Kotlin Multiplatform 2.1.0 | Business logic, state management |
| **DI** | Koin 4.0 | Multi-platform dependency injection |
| **VPN Engine** | Rust + arti-client | Tor bootstrapping, SOCKS5 proxy |
| **FFI** | UniFFI 0.28 | Auto-generates Kotlin/Swift/C bindings |
| **Async** | Tokio 1.x | Async I/O in Rust core |
| **Android Tunnel** | VpnService + NDK JNI | Phone + TV TUN fd management |
| **iOS Tunnel** | NetworkExtension (PacketTunnelProvider) | Packet interception |
| **tvOS Tunnel** | NetworkExtension (AppProxyProvider) | App-level proxy on Apple TV |
| **macOS Tunnel** | utun + pfctl | System route override |
| **Linux Tunnel** | /dev/net/tun + iptables | Full traffic redirect (desktop + router) |
| **Windows Tunnel** | Wintun driver | High-speed TUN interface |
| **Auth** | Firebase Auth | Email/Password + Google Sign-In |
| **CI/CD** | GitHub Actions | 9-platform parallel builds |

---

## 📁 Project Structure

```
NodeX-VPN/
├── 📦 rust-core/                    # Rust VPN engine (shared by all 9 platforms)
│   ├── src/
│   │   ├── lib.rs                   # UniFFI exported API
│   │   ├── tor_manager.rs           # arti-client lifecycle
│   │   ├── tun2socks.rs             # IP packet → SOCKS5 relay
│   │   ├── stats.rs                 # Bandwidth + circuit stats
│   │   ├── dns.rs                   # DNS-over-Tor
│   │   ├── node_registry.rs         # Exit node catalogue (18 countries)
│   │   ├── logging.rs               # Ring-buffer log system
│   │   └── tunnel/
│   │       ├── linux.rs             # TUN + iptables (Linux desktop + router)
│   │       ├── macos.rs             # utun + pfctl
│   │       └── windows.rs           # Wintun driver
│   └── Cargo.toml
│
├── 📱 shared/                       # Kotlin Multiplatform
│   └── src/
│       ├── commonMain/kotlin/
│       │   ├── ui/screens/          # Splash, Onboarding, Auth, Dashboard, Settings, Logs
│       │   ├── ui/tv/               # TvApp, TvDashboard, TvServerList, TvSettings
│       │   ├── ui/responsive/       # AdaptiveNav, AdaptiveLayout, WindowSizeClass
│       │   └── ui/theme/            # Cyberpunk Material3 dark theme
│       ├── androidMain/             # Android platform actuals
│       ├── iosMain/                 # iOS platform actuals
│       ├── tvosMain/                # tvOS platform actuals (NEW)
│       └── desktopMain/             # Desktop platform actuals
│
├── 🤖 androidApp/                   # Android phone/tablet
├── 📺 androidTvApp/                 # Android TV (NEW)
│   ├── src/main/kotlin/
│   │   ├── TvMainActivity.kt        # Leanback entry point
│   │   └── TvApplication.kt
│   └── src/main/res/drawable/
│       └── tv_banner.xml            # 320×180dp TV launcher banner
│
├── 🍎 iosApp/                       # iOS Xcode project
│   ├── iosApp/                      # SwiftUI app + Firebase
│   └── NodeXTunnel/                 # PacketTunnelProvider
│
├── 📺 tvosApp/                      # Apple TV Xcode project (NEW)
│   ├── tvosApp/
│   │   ├── tvOSApp.swift            # @main SwiftUI entry
│   │   ├── AppDelegate.swift        # Firebase init + VPN status
│   │   ├── ContentView.swift        # Compose ↔ SwiftUI bridge
│   │   └── Assets.xcassets/
│   │       └── AppIcon~tv.appiconset/   # 400×240, 800×480, 1280×768 PNG icons
│   ├── NodeXTvTunnel/
│   │   └── AppProxyProvider.swift   # NEAppProxyProvider VPN tunnel
│   └── NodeXTvVPN.xcworkspace
│
├── 🖥️ desktopApp/                   # JVM desktop (Win/macOS/Linux)
│
├── 🏠 router/                       # Router + NAS deployment system (NEW)
│   ├── install.sh                   # Universal: auto-detects all 5 platforms
│   ├── setup_openwrt.sh             # OpenWrt: kmod-tun, iptables, UCI, hotplug
│   ├── setup_glinet_merlin.sh       # GL.iNet + Asus Merlin
│   ├── setup_pfsense_opnsense.sh    # pfSense / OPNsense: pf(4) anchor + FreeBSD rc.d
│   ├── setup_synology.sh            # Synology DSM 6/7: SPK + iptables + rc.d/Upstart
│   ├── package.sh                   # CI: .tar.gz (all targets) + .spk (Synology)
│   └── README.md
│
└── ⚙️ .github/workflows/ci.yml      # 9-platform parallel CI/CD
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| JDK | 17+ | [adoptium.net](https://adoptium.net) |
| Android Studio | Hedgehog+ | [developer.android.com](https://developer.android.com/studio) |
| Rust | stable | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| Xcode | 15+ | Mac App Store |
| CocoaPods | Latest | `sudo gem install cocoapods` |

### 1. Clone

```bash
git clone https://github.com/your-username/NodeX-VPN.git
cd NodeX-VPN
```

### 2. Firebase Setup

> ⚠️ **Never commit Firebase config files to the repo.**

| App | Bundle ID | File | Destination |
|-----|-----------|------|-------------|
| Android (phone + TV) | `com.nodex.vpn.android` | `google-services.json` | `androidApp/` |
| iOS | `com.nodex.vpn` | `GoogleService-Info.plist` | `iosApp/iosApp/` |
| tvOS | `com.nodex.vpn.tv` | `GoogleService-Info.plist` | `tvosApp/tvosApp/` |

GitHub Actions secrets: `GOOGLE_SERVICES_JSON`, `GOOGLE_SERVICE_INFO_PLIST`, `GOOGLE_SERVICE_INFO_PLIST_TVOS`

### 3. Build Rust Core

```bash
cd rust-core

# Android
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -o ../shared/src/androidMain/jniLibs build --release

# iOS
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
cargo build --release --target aarch64-apple-ios

# tvOS
rustup target add aarch64-apple-tvos aarch64-apple-tvos-sim
cargo build --release --target aarch64-apple-tvos

# Router / Linux ARM
rustup target add aarch64-unknown-linux-gnu
cargo build --release --target aarch64-unknown-linux-gnu

# Router — pfSense / OPNsense (FreeBSD cross-compile)
# Requires: brew install filosottile/musl-cross/musl-cross  (macOS)
# Or:       cargo install cross && cross build --target x86_64-unknown-freebsd
rustup target add x86_64-unknown-freebsd
cargo build --release --target x86_64-unknown-freebsd
```

### 4. Run

```bash
./gradlew :androidApp:installDebug       # Phone
./gradlew :androidTvApp:installDebug     # TV
./gradlew :desktopApp:run                # Desktop

# iOS — open iosApp/iosApp.xcworkspace in Xcode
# tvOS — open tvosApp/NodeXTvVPN.xcworkspace in Xcode

# Router — OpenWrt / GL.iNet / Merlin
ssh root@192.168.1.1
wget -O- https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/install.sh | sh

# Router — pfSense / OPNsense (SSH or Diagnostics shell)
fetch -o /tmp/setup.sh https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_pfsense_opnsense.sh
sh /tmp/setup.sh

# NAS — Synology DSM
curl -fsSL https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_synology.sh | sh
```

---

## 🛠️ Build

### Android

```bash
./gradlew :androidApp:assembleDebug          # Phone debug APK
./gradlew :androidApp:bundleRelease          # Phone release AAB
./gradlew :androidTvApp:assembleDebug        # TV debug APK
./gradlew :androidTvApp:bundleRelease        # TV release AAB
```

### iOS / tvOS

```bash
# iOS IPA
xcodebuild archive \
  -workspace iosApp/NodeXVPN.xcworkspace -scheme NodeXVPN \
  -sdk iphoneos -archivePath build/NodeXVPN.xcarchive

# tvOS IPA
xcodebuild archive \
  -workspace tvosApp/NodeXTvVPN.xcworkspace -scheme NodeXTvVPN \
  -sdk appletvos -archivePath build/NodeXTvVPN.xcarchive
```

### Desktop

```bash
./gradlew :desktopApp:packageDmg    # macOS
./gradlew :desktopApp:packageMsi    # Windows
./gradlew :desktopApp:packageDeb    # Linux DEB
./gradlew :desktopApp:packageRpm    # Linux RPM
```

### Router Packages

```bash
# Linux routers (OpenWrt, GL.iNet, Merlin, Synology)
bash router/package.sh x86_64-unknown-linux-gnu    # x86_64 tarball + Synology SPK
bash router/package.sh aarch64-unknown-linux-gnu   # ARM64  tarball + Synology SPK

# pfSense / OPNsense (FreeBSD)
bash router/package.sh x86_64-unknown-freebsd      # FreeBSD tarball

# Output:
#   dist/router/nodex-vpn-router-x86_64-unknown-linux-gnu-<ver>.tar.gz
#   dist/router/NodeX-VPN-<ver>-x86_64.spk        ← Synology Package Center
#   dist/router/nodex-vpn-router-x86_64-unknown-freebsd-<ver>.tar.gz
```

---

## ⚙️ CI/CD

### Pipeline — 9 Jobs

```
Push / PR to main
       │
┌──────▼─────────────────────────────────────────────────────────────────┐
│  Job 1: rust-core  (matrix — 15 targets)                               │
│  android×4  ios×3  tvos×2  macos×2  linux×2  windows×2                │
└──────┬─────────────────────────────────────────────────────────────────┘
       │ artifacts: .so / .a / .dylib / .dll
┌──────▼─────────────────────────────────────────────────────────────────┐
│  Jobs 2–8 (parallel)                                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────┐ ┌──────┐ ┌────────┐ ┌─────────┐  │
│  │ Android  │ │Android TV│ │ iOS  │ │tvOS  │ │Desktop │ │ Router  │  │
│  │ APK+AAB  │ │ APK+AAB  │ │ IPA  │ │ IPA  │ │DMG/MSI │ │ tar.gz  │  │
│  │          │ │          │ │      │ │      │ │DEB/RPM │ │ ×2 arch │  │
│  └──────────┘ └──────────┘ └──────┘ └──────┘ └────────┘ └─────────┘  │
└──────┬─────────────────────────────────────────────────────────────────┘
       │ (on git tag v*)
┌──────▼──────────────────────────────┐
│  Job 9: GitHub Release              │
│  All artifacts uploaded             │
│  Auto-generated release notes       │
└─────────────────────────────────────┘
```

### GitHub Secrets

| Secret | Used By |
|--------|---------|
| `GOOGLE_SERVICES_JSON` | Android job |
| `GOOGLE_SERVICE_INFO_PLIST` | iOS job |
| `GOOGLE_SERVICE_INFO_PLIST_TVOS` | tvOS job |
| `KEYSTORE_BASE64` | Android + Android TV |
| `KEYSTORE_PASS` / `KEY_ALIAS` / `KEY_PASS` | Android signing |

### Build Targets Matrix

| Target | Platform | Output |
|--------|----------|--------|
| `aarch64-linux-android` | Android ARM64 | `.so` |
| `armv7-linux-androideabi` | Android ARMv7 | `.so` |
| `x86_64-linux-android` | Android x86_64 | `.so` |
| `i686-linux-android` | Android x86 | `.so` |
| `aarch64-apple-ios` | iPhone/iPad | `.a` |
| `x86_64-apple-ios` | iOS Simulator | `.a` |
| `aarch64-apple-ios-sim` | iOS Sim ARM64 | `.a` |
| `aarch64-apple-tvos` | Apple TV | `.a` |
| `aarch64-apple-tvos-sim` | tvOS Simulator | `.a` |
| `x86_64-apple-darwin` | Intel Mac | `.dylib` |
| `aarch64-apple-darwin` | Apple Silicon | `.dylib` |
| `x86_64-unknown-linux-gnu` | Linux x64 + router + Synology SPK | `.so` + `.tar.gz` + `.spk` |
| `aarch64-unknown-linux-gnu` | Linux ARM64 + router + Synology SPK | `.so` + `.tar.gz` + `.spk` |
| `x86_64-unknown-freebsd` | pfSense / OPNsense | `.tar.gz` |
| `x86_64-pc-windows-msvc` | Windows x64 | `.dll` |
| `aarch64-pc-windows-msvc` | Windows ARM64 | `.dll` |

---

## 🔒 Security & Privacy

| Property | Value |
|----------|-------|
| **Logs** | None — zero user data stored |
| **Owned Servers** | None — uses Tor volunteer relays |
| **Traffic Encryption** | 3-layer onion encryption (Tor standard) |
| **DNS** | Resolved via Tor — no DNS leaks |
| **Code** | Open source — auditable |
| **Auth data** | Firebase Auth (email hash only, no VPN usage data) |

✅ **Protects against:** ISP surveillance, government monitoring, geo-blocks, DPI  
✅ **Bypasses:** Firewalls with obfs4 bridges  
⚠️ **Router UDP caveat:** Tor is TCP-only — UDP game traffic (PS5, Xbox Live) uses real IP  

---

## ❓ FAQ

**Q: My Smart TV has no VPN app. Can I protect it?**  
Yes — install NodeX VPN on your router. Every device on your LAN (Smart TV, Roku, PS5, Xbox, IoT) is automatically routed through Tor with zero setup on those devices.

**Q: Android TV vs regular Android — same APK?**  
No. `androidApp` is for phones/tablets. `androidTvApp` has Leanback launcher integration, D-pad navigation, and a TV-optimized UI. Both share the same Rust VPN core and JNI libs.

**Q: Does the Apple TV app support full VPN?**  
Yes, via `NEAppProxyProvider`. tvOS 16+ allows full proxy-level VPN through NetworkExtension. The same Rust/Tor engine powers it as on iOS.

**Q: Can I change countries without disconnecting?**  
Yes. Select any of 18 exit countries — the circuit rebuilds seamlessly in the background.

**Q: Does it work in censored regions?**  
Yes, with obfs4 bridges enabled. Traffic is disguised as HTTPS. Works in China, Iran, Russia, UAE.

**Q: Is google-services.json in the repo?**  
Never — injected at build time via CI secrets only.

---

## 🤝 Contributing

**High-priority contributions:**
- Ubiquiti EdgeOS / UniFi OS support (`setup_unifi.sh`)
- Amazon Fire TV Play Store listing
- DD-WRT / Tomato full support (`setup_ddwrt.sh`)
- UDP relay via QUIC-over-Tor research

```bash
git checkout -b feature/your-feature
cargo test && ./gradlew test
# Open a Pull Request
```

---

## 📄 License

```
MIT License — Copyright (c) 2025 NodeX Project
See LICENSE file for full text.
```

---

<div align="center">

**Built with ❤️ using Kotlin Multiplatform + Rust + Tor**

*Anonymity is a human right.*

</div>
