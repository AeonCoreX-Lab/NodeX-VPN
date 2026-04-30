# NodeX VPN — Release Notes

All notable changes to NodeX VPN are documented here.
Versioning follows [Semantic Versioning](https://semver.org) — `MAJOR.MINOR.PATCH`.

---

## [0.1.0] — 2026-04-26  *(Initial Release)*

**Core Engine**
- Production Tor engine via `arti-client 0.41`
- Real bootstrap with event-driven progress tracking
- Production SOCKS5 proxy backed by arti circuits
- Real exit-node country selection via stream isolation
- Bridge (obfs4) configuration support
- DNS-over-Tor listener

**Platforms**
- Android (arm64-v8a, armeabi-v7a, x86_64, x86)
- Android TV
- iOS / iPadOS
- tvOS (Apple TV)
- macOS (Apple Silicon + Intel)
- Windows (x64 + ARM64)
- Linux (x86_64 + aarch64)
- FreeBSD x86_64 (pfSense / OPNsense router package)

**CLI** (`nodex` binary)
- `nodex connect` — connect with live bootstrap progress + stats
- `nodex status`  — show current connection stats
- `nodex nodes`   — list available VPN nodes
- `nodex logs`    — view recent logs
- `nodex version` — show full version and build info
- Fully supported: Linux, macOS, Windows, Termux (Android)
- ANSI color with auto-detection; respects `NO_COLOR`

---

## Versioning Policy

| Increment | When                                            |
|-----------|-------------------------------------------------|
| `MAJOR`   | Breaking API or protocol change                 |
| `MINOR`   | New feature, new platform, new CLI subcommand   |
| `PATCH`   | Bug fix, dependency update, CI fix              |

To create a release, push a version tag:
```bash
git tag v0.2.0
git push origin v0.2.0
```
GitHub Actions will build all 16 platform targets and publish a GitHub Release automatically.
