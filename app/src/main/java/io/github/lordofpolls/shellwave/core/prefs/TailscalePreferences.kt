package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val KEY_TS_ENABLED = "tailscale_enabled"
private const val KEY_TS_AUTO_START = "tailscale_auto_start"
private const val KEY_TS_AUTO_ROUTE_SUBNETS = "tailscale_auto_route_subnets"
private const val KEY_TS_HOSTNAME = "tailscale_hostname"
private const val KEY_TS_CONTROL_URL = "tailscale_control_url"

private const val PREFIX_HOST_USE_TS = "host_tailscale_use_"
private const val PREFIX_HOST_TS_SSH = "host_tailscale_ssh_"

data class HostTailscaleConfig(
    val useTailscale: Boolean = false,
    val useTailscaleSsh: Boolean = false,
)

/**
 * Global and per-host preferences for ShellSurge's embedded userspace Tailscale (`tsnet`) node.
 */
object TailscalePreferences {
    fun isEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_TS_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_TS_ENABLED, enabled) }
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_TS_AUTO_START, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_TS_AUTO_START, enabled) }
    }

    fun isAutoRouteSubnetsEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_TS_AUTO_ROUTE_SUBNETS, true)

    fun setAutoRouteSubnetsEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_TS_AUTO_ROUTE_SUBNETS, enabled) }
    }

    fun getHostname(context: Context): String =
        sharedPrefs(context).getString(KEY_TS_HOSTNAME, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "shellsurge"

    fun setHostname(context: Context, hostname: String) {
        val clean = hostname.trim()
        sharedPrefs(context).edit {
            if (clean.isEmpty()) remove(KEY_TS_HOSTNAME) else putString(KEY_TS_HOSTNAME, clean)
        }
    }

    fun getControlUrl(context: Context): String =
        sharedPrefs(context).getString(KEY_TS_CONTROL_URL, null)?.trim().orEmpty()

    fun setControlUrl(context: Context, url: String) {
        val clean = url.trim()
        sharedPrefs(context).edit {
            if (clean.isEmpty()) remove(KEY_TS_CONTROL_URL) else putString(KEY_TS_CONTROL_URL, clean)
        }
    }

    fun getHostConfig(context: Context, hostId: Long): HostTailscaleConfig {
        val prefs = sharedPrefs(context)
        return HostTailscaleConfig(
            useTailscale = prefs.getBoolean("$PREFIX_HOST_USE_TS$hostId", false),
            useTailscaleSsh = prefs.getBoolean("$PREFIX_HOST_TS_SSH$hostId", false),
        )
    }

    fun saveHostConfig(context: Context, hostId: Long, config: HostTailscaleConfig) {
        sharedPrefs(context).edit {
            if (config.useTailscale) {
                putBoolean("$PREFIX_HOST_USE_TS$hostId", true)
            } else {
                remove("$PREFIX_HOST_USE_TS$hostId")
            }
            if (config.useTailscale && config.useTailscaleSsh) {
                putBoolean("$PREFIX_HOST_TS_SSH$hostId", true)
            } else {
                remove("$PREFIX_HOST_TS_SSH$hostId")
            }
        }
    }

    fun clearHostConfig(context: Context, hostId: Long) {
        sharedPrefs(context).edit {
            remove("$PREFIX_HOST_USE_TS$hostId")
            remove("$PREFIX_HOST_TS_SSH$hostId")
        }
    }

    fun copyHostConfig(context: Context, fromHostId: Long, toHostId: Long) {
        val existing = getHostConfig(context, fromHostId)
        if (existing.useTailscale) {
            saveHostConfig(context, toHostId, existing)
        }
    }

    /**
     * Returns true when [hostname] looks like a Tailscale MagicDNS name (`*.ts.net`) or a CGNAT
     * Tailscale IPv4 address (`100.64.0.0/10`: `100.64.x.x` – `100.127.x.x`).
     */
    fun looksLikeTailscaleHost(hostname: String): Boolean {
        val clean = hostname.trim().lowercase().removeSuffix(".")
        if (clean.isEmpty()) return false
        if (clean == "ts.net" || clean.endsWith(".ts.net")) return true
        val parts = clean.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it < 0 || it > 255 }) return false
            if (octets[0] == 100 && octets[1] in 64..127) return true
        }
        return false
    }
}
