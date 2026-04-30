# NodeX VPN ProGuard Rules

# ── Keep JNI bridge classes ───────────────────────────────────────────────────
-keep class com.nodex.vpn.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Koin ─────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* *;
}

# ── Ktor ─────────────────────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Kotlinx Serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    static **$$serializer INSTANCE;
}

# ── Android VPN Service ───────────────────────────────────────────────────────
-keep class android.net.VpnService { *; }
-keep class com.nodex.vpn.android.NodeXVpnService { *; }

# ── Suppress warnings ─────────────────────────────────────────────────────────
-dontwarn kotlin.**
-dontwarn kotlinx.**
