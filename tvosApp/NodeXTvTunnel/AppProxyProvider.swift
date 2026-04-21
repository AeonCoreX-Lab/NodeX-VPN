// tvosApp/NodeXTvTunnel/AppProxyProvider.swift
//
// NodeX VPN — tvOS App Proxy Extension
//
// ARCHITECTURE:
//   tvOS does NOT support NEPacketTunnelProvider (full TUN-level packet tunnel).
//   tvOS DOES support NEAppProxyProvider (TCP/UDP app-level proxy, tvOS 16+).
//
//   This extension acts as a SOCKS5 client proxy:
//     1. The main NodeX VPN app on Apple TV discovers a paired iPhone/Mac
//        running NodeX VPN via Bonjour (_nodex-vpn._tcp.local) or via
//        user-configured IP in Settings.
//     2. The main app configures this NEAppProxyProvider with the iPhone's
//        Tor SOCKS5 proxy address (default: <iphone-ip>:9050).
//     3. Apple TV's network stack sends app connections through this extension.
//     4. This extension forwards each connection to the SOCKS5 proxy on iPhone.
//
// RESULT: All Apple TV app traffic travels through Tor on the paired iPhone,
// providing anonymity without running the Rust core natively on tvOS.
//
// ALTERNATIVE (no iPhone):
//   Users can also configure a VPN profile (IKEv2/WireGuard) on their router
//   and point Apple TV at that. See NodeX Router docs for OpenWrt setup.

import NetworkExtension
import Network
import os.log

private let log = OSLog(subsystem: "com.nodex.vpn.tvos.proxy", category: "AppProxy")

// MARK: - NodeXAppProxyProvider

class NodeXAppProxyProvider: NEAppProxyProvider {

    // Proxy config received from the main app
    private var proxyHost: String = "10.0.0.1"   // Default: gateway; overridden by app
    private var proxyPort: UInt16 = 9050          // Tor SOCKS5 default port

    // Connection tracking
    private var activeConnections: [String: NWConnection] = [:]
    private let queue = DispatchQueue(label: "com.nodex.vpn.tvos.proxy.queue")

    // MARK: - Lifecycle

    override func startProxy(
        options: [String: Any]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        os_log("NodeXAppProxyProvider: startProxy", log: log, type: .info)

        // Read proxy address from configuration set by main app
        if let cfg = (self.protocolConfiguration as? NEAppProxyProviderProtocol)?
                        .providerConfiguration {
            proxyHost = cfg["proxyHost"] as? String ?? proxyHost
            proxyPort = (cfg["proxyPort"] as? NSNumber)?.uint16Value ?? proxyPort
        }

        os_log("SOCKS5 proxy target: %{public}@:%d",
               log: log, type: .info, proxyHost, proxyPort)

        // Verify we can reach the proxy before reporting success
        verifyProxyReachability { [weak self] reachable in
            if reachable {
                os_log("Proxy reachable — proxy started", log: log, type: .info)
                completionHandler(nil)
            } else {
                os_log("Proxy unreachable — check iPhone NodeX VPN is running",
                       log: log, type: .error)
                completionHandler(ProxyError.proxyUnreachable(host: self?.proxyHost ?? "?",
                                                              port: self?.proxyPort ?? 0))
            }
        }
    }

    override func stopProxy(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        os_log("NodeXAppProxyProvider: stopProxy (reason=%d)",
               log: log, type: .info, reason.rawValue)
        queue.async { [weak self] in
            self?.activeConnections.values.forEach { $0.cancel() }
            self?.activeConnections.removeAll()
        }
        completionHandler()
    }

    // MARK: - TCP Flow Handling

    override func handleNewFlow(_ flow: NEAppProxyFlow) -> Bool {
        // Only handle TCP streams (NEAppProxyTCPFlow)
        guard let tcpFlow = flow as? NEAppProxyTCPFlow else {
            // UDP handled separately; return false to let system handle it
            return false
        }

        let remoteEndpoint = tcpFlow.remoteEndpoint as? NWHostEndpoint
        let host = remoteEndpoint?.hostname ?? "unknown"
        let port = remoteEndpoint?.port     ?? "0"
        let flowID = "\(host):\(port)-\(UUID().uuidString.prefix(8))"

        os_log("New TCP flow → %{public}@:%{public}@", log: log, type: .debug, host, port)

        // Open connection to our SOCKS5 proxy on the iPhone
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(proxyHost),
            port: NWEndpoint.Port(rawValue: proxyPort) ?? 9050
        )
        let conn = NWConnection(to: endpoint, using: .tcp)

        queue.async { [weak self] in
            self?.activeConnections[flowID] = conn
        }

        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self = self, let conn = conn else { return }
            switch state {
            case .ready:
                // Send SOCKS5 handshake then relay flow ↔ proxy
                self.socks5Handshake(conn: conn, host: host, port: UInt16(port) ?? 80) {
                    [weak self] error in
                    if let error = error {
                        os_log("SOCKS5 handshake failed: %{public}@",
                               log: log, type: .error, error.localizedDescription)
                        tcpFlow.closeReadWithError(error)
                        tcpFlow.closeWriteWithError(error)
                        conn.cancel()
                    } else {
                        self?.relay(flow: tcpFlow, conn: conn, flowID: flowID)
                    }
                }
            case .failed(let error):
                os_log("Proxy connection failed: %{public}@",
                       log: log, type: .error, error.localizedDescription)
                tcpFlow.closeReadWithError(error)
                tcpFlow.closeWriteWithError(error)
                self.queue.async { self.activeConnections.removeValue(forKey: flowID) }
            case .cancelled:
                self.queue.async { self.activeConnections.removeValue(forKey: flowID) }
            default:
                break
            }
        }

        conn.start(queue: queue)
        tcpFlow.open(completionHandler: { _ in })   // open the flow side
        return true
    }

    // MARK: - SOCKS5 Handshake

    // Implements RFC 1928 (SOCKS5) with no-auth (method 0x00),
    // which is what Tor's SOCKS5 server expects on localhost.
    private func socks5Handshake(
        conn: NWConnection,
        host: String,
        port: UInt16,
        completion: @escaping (Error?) -> Void
    ) {
        // Step 1: Greeting — request no-auth method
        let greeting = Data([0x05, 0x01, 0x00])  // VER=5, NMETHODS=1, METHOD=NO_AUTH
        conn.send(content: greeting, completion: .contentProcessed { [weak conn] error in
            guard let conn = conn, error == nil else { completion(error ?? ProxyError.connectionLost); return }

            // Step 2: Read server's method selection
            conn.receive(minimumIncompleteLength: 2, maximumLength: 2) { data, _, _, error in
                guard error == nil, let data = data, data.count == 2,
                      data[0] == 0x05, data[1] == 0x00 else {
                    completion(error ?? ProxyError.socks5AuthFailed)
                    return
                }

                // Step 3: Send CONNECT request
                var request = Data([0x05, 0x01, 0x00, 0x03])  // VER, CMD=CONNECT, RSV, ATYP=DOMAIN
                let hostBytes = host.utf8
                request.append(UInt8(hostBytes.count))
                request.append(contentsOf: hostBytes)
                request.append(UInt8((port >> 8) & 0xFF))
                request.append(UInt8(port & 0xFF))

                conn.send(content: request, completion: .contentProcessed { [weak conn] error in
                    guard let conn = conn, error == nil else { completion(error ?? ProxyError.connectionLost); return }

                    // Step 4: Read CONNECT response (minimum 10 bytes)
                    conn.receive(minimumIncompleteLength: 10, maximumLength: 256) { data, _, _, error in
                        guard error == nil, let data = data, data.count >= 10,
                              data[0] == 0x05, data[1] == 0x00 else {
                            completion(error ?? ProxyError.socks5ConnectFailed)
                            return
                        }
                        // Success — tunnel is established
                        completion(nil)
                    }
                })
            }
        })
    }

    // MARK: - Bidirectional Relay

    private func relay(flow: NEAppProxyTCPFlow, conn: NWConnection, flowID: String) {
        // flow → proxy
        readFromFlow(flow: flow, conn: conn, flowID: flowID)
        // proxy → flow
        readFromProxy(conn: conn, flow: flow, flowID: flowID)
    }

    private func readFromFlow(flow: NEAppProxyTCPFlow, conn: NWConnection, flowID: String) {
        flow.readData { [weak self, weak conn] data, error in
            guard let self = self, let conn = conn else { return }
            if let error = error {
                os_log("Flow read error: %{public}@", log: log, type: .debug, error.localizedDescription)
                conn.cancel()
                return
            }
            guard let data = data, !data.isEmpty else {
                conn.cancel(); return
            }
            conn.send(content: data, completion: .contentProcessed { error in
                if error == nil { self.readFromFlow(flow: flow, conn: conn, flowID: flowID) }
            })
        }
    }

    private func readFromProxy(conn: NWConnection, flow: NEAppProxyTCPFlow, flowID: String) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak conn] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty {
                flow.write(data) { error in
                    if error == nil {
                        self.readFromProxy(conn: conn!, flow: flow, flowID: flowID)
                    }
                }
            } else if isComplete || error != nil {
                flow.closeReadWithError(error)
                conn?.cancel()
                self.queue.async { self.activeConnections.removeValue(forKey: flowID) }
            }
        }
    }

    // MARK: - Proxy Reachability Check

    private func verifyProxyReachability(completion: @escaping (Bool) -> Void) {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(proxyHost),
            port: NWEndpoint.Port(rawValue: proxyPort) ?? 9050
        )
        let conn = NWConnection(to: endpoint, using: .tcp)
        var done = false
        conn.stateUpdateHandler = { state in
            guard !done else { return }
            switch state {
            case .ready:
                done = true; conn.cancel(); completion(true)
            case .failed, .cancelled:
                done = true; completion(false)
            default: break
            }
        }
        conn.start(queue: DispatchQueue.global())
        // 5 second timeout
        DispatchQueue.global().asyncAfter(deadline: .now() + 5) {
            if !done { done = true; conn.cancel(); completion(false) }
        }
    }
}

// MARK: - Errors

enum ProxyError: Error, LocalizedError {
    case proxyUnreachable(host: String, port: UInt16)
    case socks5AuthFailed
    case socks5ConnectFailed
    case connectionLost

    var errorDescription: String? {
        switch self {
        case .proxyUnreachable(let h, let p):
            return "Cannot reach SOCKS5 proxy at \(h):\(p). Make sure NodeX VPN is running on your iPhone."
        case .socks5AuthFailed:
            return "SOCKS5 authentication failed."
        case .socks5ConnectFailed:
            return "SOCKS5 CONNECT request failed."
        case .connectionLost:
            return "Connection to proxy lost."
        }
    }
}
