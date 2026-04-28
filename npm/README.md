# @aeoncorex/nodex-vpn

**NodeX VPN** — Tor-based privacy VPN CLI by [AeonCoreX](https://github.com/AeonCoreX/NodeX-VPN)

```
  ███╗   ██╗ ██████╗ ██████╗ ███████╗██╗  ██╗
  ████╗  ██║██╔═══██╗██╔══██╗██╔════╝╚██╗██╔╝
  ██╔██╗ ██║██║   ██║██║  ██║█████╗   ╚███╔╝
  ██║╚██╗██║██║   ██║██║  ██║██╔══╝   ██╔██╗
  ██║ ╚████║╚██████╔╝██████╔╝███████╗██╔╝ ██╗
  ╚═╝  ╚═══╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝
              V  P  N  ·  Powered by AeonCoreX
```

## Install

```bash
# Global install (recommended)
npm install -g @aeoncorex/nodex-vpn

# Or run without installing
npx @aeoncorex/nodex-vpn connect --country DE
```

## Supported Platforms

| Platform         | Architecture |
|------------------|--------------|
| Linux            | x64, arm64   |
| macOS            | x64, arm64   |
| Windows          | x64, arm64   |
| Termux (Android) | arm64        |

## Usage

```bash
nodex connect                          # Auto exit country
nodex connect --country DE             # Germany exit node
nodex connect --country US --verbose   # Debug logging
nodex connect --bridge "obfs4 ..."     # Bridge mode (censored regions)
nodex status                           # Live connection stats
nodex nodes                            # List VPN nodes
nodex nodes --country NL               # Filter by country
nodex logs --lines 100                 # Recent logs
nodex version                          # Version + build info
```

## How it works

NodeX VPN routes your traffic through the **Tor network** — no servers owned by AeonCoreX.

```
Your Device → Guard Relay → Middle Relay → Exit Relay → Internet
```

- **Zero logs** — no user data stored
- **No owned servers** — Tor volunteer relays
- **obfs4 bridges** — bypasses censorship in China, Iran, Russia

## License

MIT © AeonCoreX
