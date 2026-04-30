// tvosApp/tvosApp/tvOSApp.swift
import SwiftUI
import shared   // KMP shared framework

@main
struct NodeXTvApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
