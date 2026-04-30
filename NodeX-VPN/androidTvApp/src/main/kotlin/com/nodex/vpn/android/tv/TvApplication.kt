package com.nodex.vpn.android.tv

import android.app.Application
import com.google.firebase.FirebaseApp
import com.nodex.vpn.auth.AuthRepository
import com.nodex.vpn.di.appModule
import com.nodex.vpn.platform.PlatformVpnBridge
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class TvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        PlatformVpnBridge.applicationContext = applicationContext
        AuthRepository.applicationContext   = applicationContext
        startKoin {
            androidContext(this@TvApplication)
            modules(appModule)
        }
    }
}
