// iosApp/iosApp/iOSApp.swift
import SwiftUI
import shared   // KMP shared framework

@main
struct iOSApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)   // Force dark — matches NodeX theme
        }
    }
}
