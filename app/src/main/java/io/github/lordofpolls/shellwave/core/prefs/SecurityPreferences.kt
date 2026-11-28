package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

enum class AppLockTimeout(val label: String, val durationMs: Long) {
    IMMEDIATE("Immediately", 0L),
    ONE_MINUTE("1 min", 60_000L),
    FIVE_MINUTES("5 min", 5 * 60_000L),
}

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_HIDE_IN_RECENTS = "security_hide_in_recents"
private const val KEY_APP_LOCK_ENABLED = "security_app_lock"
private const val KEY_APP_LOCK_TIMEOUT = "security_app_lock_timeout"

/**
 * Pure timeout decision helper so background lock grace intervals are JVM-testable.
 */
internal fun shouldLockApp(
    enabled: Boolean,
    backgroundedAtMs: Long?,
    nowMs: Long,
    timeoutMs: Long,
): Boolean {
    if (!enabled || backgroundedAtMs == null) return false
    if (timeoutMs <= 0L) return true
    return (nowMs - backgroundedAtMs) >= timeoutMs
}

object SecurityPreferences {
    fun isHideInRecentsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_IN_RECENTS, false)

    fun setHideInRecentsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_HIDE_IN_RECENTS, enabled) }
    }

    fun isAppLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_APP_LOCK_ENABLED, enabled) }
    }

    fun getAppLockTimeout(context: Context): AppLockTimeout {
        val raw = prefs(context).getString(KEY_APP_LOCK_TIMEOUT, null)
        return raw?.let { runCatching { AppLockTimeout.valueOf(it) }.getOrNull() }
            ?: AppLockTimeout.ONE_MINUTE
    }

    fun setAppLockTimeout(context: Context, timeout: AppLockTimeout) {
        prefs(context).edit { putString(KEY_APP_LOCK_TIMEOUT, timeout.name) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
