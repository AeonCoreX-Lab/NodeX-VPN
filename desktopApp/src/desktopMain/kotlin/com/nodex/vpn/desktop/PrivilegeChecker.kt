// desktopApp/src/desktopMain/kotlin/com/nodex/vpn/desktop/PrivilegeChecker.kt
package com.nodex.vpn.desktop

import java.io.File

/**
 * Platform-aware privilege checker.
 *
 * Linux  – needs CAP_NET_ADMIN or root to create /dev/net/tun devices.
 *           If missing, relaunches self with pkexec / sudo.
 * macOS  – needs root or the SystemExtension entitlement.
 *           Relaunches with `osascript -e 'do shell script … with administrator privileges'`
 * Windows – needs Administrator token (UAC elevation).
 *           Relaunches via ShellExecuteW with "runas" verb.
 */
object PrivilegeChecker {

    private val os = System.getProperty("os.name", "").lowercase()

    fun hasTunPermission(): Boolean = when {
        os.contains("linux")   -> isLinuxRoot()
        os.contains("mac")     -> isMacRoot()
        os.contains("windows") -> isWindowsAdmin()
        else                   -> true // unsupported – attempt anyway
    }

    fun requestPrivilegesAndRelaunch() {
        val exe = ProcessHandle.current().info().command().orElse("java")
        val args = ProcessHandle.current().info().arguments()
            .map { it.toList() }.orElse(emptyList())
        val cmdArgs = (listOf(exe) + args).joinToString(" ")

        when {
            os.contains("linux") -> {
                val pkexec = File("/usr/bin/pkexec")
                if (pkexec.exists()) {
                    ProcessBuilder("pkexec", exe, *args.toTypedArray())
                        .inheritIO()
                        .start()
                } else {
                    // Fallback: xterm + sudo
                    ProcessBuilder(
                        "xterm", "-e",
                        "sudo $cmdArgs; read -p 'Press Enter to close'",
                    ).inheritIO().start()
                }
            }

            os.contains("mac") -> {
                val script = """osascript -e 'do shell script "$cmdArgs" with administrator privileges'"""
                ProcessBuilder("/bin/bash", "-c", script)
                    .inheritIO()
                    .start()
            }

            os.contains("windows") -> {
                // PowerShell UAC elevation
                val ps = """
                    Start-Process '$exe' -ArgumentList '${args.joinToString(" ")}' -Verb RunAs
                """.trimIndent()
                ProcessBuilder("powershell.exe", "-Command", ps)
                    .inheritIO()
                    .start()
            }
        }
        System.exit(0)
    }

    // ── OS-specific checks ────────────────────────────────────────────────────

    private fun isLinuxRoot(): Boolean {
        return try {
            // Check effective UID == 0, or CAP_NET_ADMIN via /proc/self/status
            val uid = ProcessBuilder("id", "-u").start()
                .inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull()
            if (uid == 0) return true

            // Check capabilities
            val status = File("/proc/self/status").readText()
            val capEff = status.lineSequence()
                .firstOrNull { it.startsWith("CapEff:") }
                ?.substringAfter("CapEff:")
                ?.trim()
                ?.toLong(16) ?: 0L
            // CAP_NET_ADMIN = bit 12
            (capEff shr 12) and 1L == 1L
        } catch (_: Exception) { false }
    }

    private fun isMacRoot(): Boolean = try {
        val uid = ProcessBuilder("id", "-u").start()
            .inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull()
        uid == 0
    } catch (_: Exception) { false }

    private fun isWindowsAdmin(): Boolean = try {
        val output = ProcessBuilder("net", "session").start()
            .inputStream.bufferedReader().readText()
        output.isNotBlank()
    } catch (_: Exception) { false }
}
