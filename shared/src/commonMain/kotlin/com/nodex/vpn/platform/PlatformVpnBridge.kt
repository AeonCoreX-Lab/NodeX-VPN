// shared/src/commonMain/kotlin/com/nodex/vpn/platform/PlatformVpnBridge.kt
package com.nodex.vpn.platform

import com.nodex.vpn.manager.BootstrapStatus
import com.nodex.vpn.manager.RawVpnStats
import com.nodex.vpn.manager.RustVpnConfig

/**
 * `expect` declaration – each platform provides an `actual` implementation:
 *   Android  → calls the native Rust JNI bindings through VpnService
 *   iOS      → calls Swift NetworkExtension + Rust xcframework
 *   Desktop  → calls Rust JNI/JNA directly
 */
expect class PlatformVpnBridge() {

    /** Trigger OS permission dialog (Android foreground service / iOS entitlement check). */
    suspend fun prepare()

    /** Start the Rust core engine with the given configuration. */
    suspend fun startEngine(config: RustVpnConfig)

    /** Gracefully stop the engine. */
    suspend fun stopEngine()

    /** Block until bootstrap reaches 100 % or throws on error. */
    suspend fun awaitBootstrap()

    /** Change the exit country on a live connection. */
    suspend fun setExitNode(isoCode: String)

    /** Snapshot of bandwidth / circuit statistics from Rust. */
    fun getRealTimeStats(): RawVpnStats

    /** Current bootstrap phase + percent. */
    fun getBootstrapStatus(): BootstrapStatus

    /** OS-specific writable state directory path passed to arti. */
    fun stateDirectory(): String

    /** OS-specific cache directory path passed to arti. */
    fun cacheDirectory(): String
}
