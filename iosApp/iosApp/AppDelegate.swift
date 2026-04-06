// iosApp/iosApp/AppDelegate.swift
import UIKit
import FirebaseCore
import GoogleSignIn
import NetworkExtension

class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // ── Firebase init ── GoogleService-Info.plist injected via CI secret
        FirebaseApp.configure()

        // ── VPN status notifications ─────────────────────────────────────────
        NotificationCenter.default.addObserver(
            self, selector: #selector(vpnStatusChanged(_:)),
            name: .NEVPNStatusDidChange, object: nil
        )

        return true
    }

    // ── Handle Google Sign-In redirect URL ───────────────────────────────────
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        return GIDSignIn.sharedInstance.handle(url)
    }

    @objc private func vpnStatusChanged(_ notification: Notification) {
        guard let conn = notification.object as? NEVPNConnection else { return }
        NotificationCenter.default.post(
            name: Notification.Name("NodeXVPNStatusChanged"),
            object: conn.status.rawValue
        )
    }
}
