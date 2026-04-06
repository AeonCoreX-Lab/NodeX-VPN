package com.nodex.vpn.di

import com.nodex.vpn.auth.AuthRepository
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.platform.PlatformVpnBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val appModule = module {
    single { PlatformVpnBridge() }
    single { AuthRepository() }
    single { AuthViewModel(repo = get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    single { VpnManager(platform = get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}
