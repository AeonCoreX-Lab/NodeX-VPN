// shared/src/tvosMain/kotlin/com/nodex/vpn/TvOSKoinHelper.kt
package com.nodex.vpn

import com.nodex.vpn.di.appModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

object TvOSKoinHelper {
    fun startKoin() {
        runCatching { stopKoin() }
        startKoin { modules(appModule) }
    }
}
