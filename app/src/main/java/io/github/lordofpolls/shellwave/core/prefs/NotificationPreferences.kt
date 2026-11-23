package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

enum class OutputIdleDelay(val seconds: Int, val label: String) {
    FAST_3S(3, "3s"),
    NORMAL_5S(5, "5s"),
    RELAXED_10S(10, "10s"),
    EXPLICIT_ONLY(0, "OSC only"),
}

private const val KEY_COMMAND_NOTIFY_ENABLED = "notify_command_output"
private const val KEY_OUTPUT_IDLE_DELAY = "notify_output_idle_delay"
private const val KEY_NOTIFY_ACTIVE_TAB = "notify_output_active_tab"

/**
 * Synchronous SharedPreferences backing for interactive command/output notifications so background
 * SSH reader threads and Compose settings stay in lockstep via [sharedPrefs] and [rememberPrefState].
 */
object NotificationPreferences {
    fun isCommandNotifyEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_COMMAND_NOTIFY_ENABLED, true)

    fun setCommandNotifyEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_COMMAND_NOTIFY_ENABLED, enabled) }
    }

    fun getIdleDelay(context: Context): OutputIdleDelay {
        val stored = sharedPrefs(context).getString(KEY_OUTPUT_IDLE_DELAY, null)
        return stored?.let { runCatching { OutputIdleDelay.valueOf(it) }.getOrNull() }
            ?: OutputIdleDelay.NORMAL_5S
    }

    fun setIdleDelay(context: Context, delay: OutputIdleDelay) {
        sharedPrefs(context).edit { putString(KEY_OUTPUT_IDLE_DELAY, delay.name) }
    }

    fun isNotifyOnActiveTab(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_NOTIFY_ACTIVE_TAB, false)

    fun setNotifyOnActiveTab(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_NOTIFY_ACTIVE_TAB, enabled) }
    }
}
