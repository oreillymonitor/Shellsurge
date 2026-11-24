package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val PREFIX_DIR = "host_startup_dir_"
private const val PREFIX_PRESET = "host_startup_preset_"
private const val PREFIX_CMD = "host_startup_cmd_"

enum class HostAutoLaunchPreset(
    val id: String,
    val label: String,
    val command: String?,
) {
    NONE("none", "None (plain shell)", null),
    CLAUDE("claude", "Claude Code (claude)", "claude"),
    CLAUDE_RESUME("claude_resume", "Claude Code — resume (claude -c)", "claude -c"),
    OPENCODE("opencode", "OpenCode (opencode)", "opencode"),
    CODEX("codex", "Codex CLI (codex)", "codex"),
    AIDER("aider", "Aider (aider)", "aider"),
    AGY("agy", "Antigravity CLI (agy)", "agy"),
    AGY_RESUME("agy_resume", "Antigravity CLI — resume (agy -c)", "agy -c"),
    CUSTOM("custom", "Custom command", null),
    ;

    companion object {
        fun fromId(id: String?): HostAutoLaunchPreset =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class HostStartupConfig(
    val startupDirectory: String? = null,
    val autoLaunchPreset: HostAutoLaunchPreset = HostAutoLaunchPreset.NONE,
    val customCommand: String? = null,
) {
    val resolvedLaunchCommand: String?
        get() = when (autoLaunchPreset) {
            HostAutoLaunchPreset.NONE -> null
            HostAutoLaunchPreset.CUSTOM -> customCommand?.trim()?.takeIf { it.isNotEmpty() }
            else -> autoLaunchPreset.command
        }

    val isEmpty: Boolean
        get() = startupDirectory.isNullOrBlank() &&
            resolvedLaunchCommand == null &&
            autoLaunchPreset == HostAutoLaunchPreset.NONE
}

/**
 * Stores per-host startup directory and auto-launch preferences keyed by saved `hostId`.
 */
object HostStartupPreferences {
    fun get(context: Context, hostId: Long): HostStartupConfig {
        val prefs = sharedPrefs(context)
        val dir = prefs.getString("$PREFIX_DIR$hostId", null)?.trim()?.takeIf { it.isNotEmpty() }
        val preset = HostAutoLaunchPreset.fromId(prefs.getString("$PREFIX_PRESET$hostId", null))
        val customCmd = prefs.getString("$PREFIX_CMD$hostId", null)?.trim()?.takeIf { it.isNotEmpty() }
        return HostStartupConfig(
            startupDirectory = dir,
            autoLaunchPreset = preset,
            customCommand = customCmd,
        )
    }

    fun save(context: Context, hostId: Long, config: HostStartupConfig) {
        val dir = config.startupDirectory?.trim()?.takeIf { it.isNotEmpty() }
        val customCmd = config.customCommand?.trim()?.takeIf { it.isNotEmpty() }
        sharedPrefs(context).edit {
            if (dir != null) putString("$PREFIX_DIR$hostId", dir) else remove("$PREFIX_DIR$hostId")
            if (config.autoLaunchPreset != HostAutoLaunchPreset.NONE) {
                putString("$PREFIX_PRESET$hostId", config.autoLaunchPreset.id)
            } else {
                remove("$PREFIX_PRESET$hostId")
            }
            if (config.autoLaunchPreset == HostAutoLaunchPreset.CUSTOM && customCmd != null) {
                putString("$PREFIX_CMD$hostId", customCmd)
            } else {
                remove("$PREFIX_CMD$hostId")
            }
        }
    }

    fun clear(context: Context, hostId: Long) {
        sharedPrefs(context).edit {
            remove("$PREFIX_DIR$hostId")
            remove("$PREFIX_PRESET$hostId")
            remove("$PREFIX_CMD$hostId")
        }
    }

    fun copy(context: Context, fromHostId: Long, toHostId: Long) {
        val existing = get(context, fromHostId)
        if (!existing.isEmpty) {
            save(context, toHostId, existing)
        }
    }
}
