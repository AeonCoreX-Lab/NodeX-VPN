// shared/src/iosMain/kotlin/com/nodex/vpn/KoinHelper.kt
package com.nodex.vpn

import com.nodex.vpn.di.appModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Called from Swift AppDelegate via KoinHelperKt.doStartKoin()
 * Kotlin objects are exposed to Obj-C/Swift with their class name + Kt suffix.
 */
object KoinHelper {
    fun startKoin() {
        runCatching { stopKoin() }   // safe if called multiple times
        startKoin { modules(appModule) }
    }
}
