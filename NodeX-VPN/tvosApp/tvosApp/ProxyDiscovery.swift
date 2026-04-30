// tvosApp/tvosApp/ProxyDiscovery.swift
//
// Discovers a NodeX VPN-running iPhone/Mac on the local network via Bonjour.
// The iPhone's NodeX VPN app broadcasts _nodex-vpn._tcp.local when connected.
// If Bonjour discovery fails, the user can enter the IP manually in Settings.

import Foundation
import Network

// MARK: - ProxyDiscovery

final class ProxyDiscovery: ObservableObject {

    @Published var discoveredHost: String? = nil
    @Published var isSearching:    Bool    = false

    private var browser: NWBrowser?
    private let serviceType = "_nodex-vpn._tcp"

    static let shared = ProxyDiscovery()
    private init() {}

    // Start Bonjour browse for NodeX VPN on LAN
    func startDiscovery() {
        guard !isSearching else { return }
        isSearching = true

        let params = NWParameters()
        params.includePeerToPeer = true

        browser = NWBrowser(for: .bonjour(type: serviceType, domain: "local."),
                            using: params)

        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            for result in results {
                if case .service(let name, _, _, _) = result.endpoint {
                    // Resolve the service to get the IP address
                    self.resolve(endpoint: result.endpoint, serviceName: name)
                }
            }
        }

        browser?.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                self?.isSearching = (state == .ready)
            }
        }

        browser?.start(queue: .global())
    }

    func stopDiscovery() {
        browser?.cancel()
        browser = nil
        DispatchQueue.main.async { self.isSearching = false }
    }

    private func resolve(endpoint: NWEndpoint, serviceName: String) {
        let conn = NWConnection(to: endpoint, using: .tcp)
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            if case .ready = state, let remote = conn?.currentPath?.remoteEndpoint {
                if case .hostPort(let host, _) = remote {
                    DispatchQueue.main.async {
                        self?.discoveredHost = "\(host)"
                    }
                }
                conn?.cancel()
            }
        }
        conn.start(queue: .global())
    }
}

// MARK: - ProxyConfig (persisted via App Group shared UserDefaults)

struct ProxyConfig {
    private static let suite = UserDefaults(suiteName: "group.com.nodex.vpn")
    static var host: String {
        get { suite?.string(forKey: "proxy_host") ?? "" }
        set { suite?.set(newValue, forKey: "proxy_host") }
    }
    static var port: UInt16 {
        get { UInt16(suite?.integer(forKey: "proxy_port") ?? 9050) }
        set { suite?.set(Int(newValue), forKey: "proxy_port") }
    }
}
