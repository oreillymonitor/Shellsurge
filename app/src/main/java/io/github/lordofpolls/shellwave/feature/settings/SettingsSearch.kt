package io.github.lordofpolls.shellwave.feature.settings

internal enum class SettingsSection(
    val title: String,
    val keywords: List<String>,
) {
    APPEARANCE(
        title = "Appearance",
        keywords = listOf(
            "theme", "mode", "light", "dark", "system", "colour", "color",
            "accent", "palette", "exact", "scheme", "amber", "phosphor",
            "green", "crt", "retro",
        ),
    ),
    TERMINAL(
        title = "Terminal & Input",
        keywords = listOf(
            "font", "size", "cursor", "scrollback", "key", "bar", "layout",
            "bell", "full-width", "configuration", "keyboard", "deck", "agent", "shell",
        ),
    ),
    NOTIFICATIONS(
        title = "Notifications",
        keywords = listOf(
            "alert", "command", "output", "idle", "delay", "osc", "active",
            "tab", "digest", "summary", "bell", "vibrate", "sound", "permission",
        ),
    ),
    CODING_AGENTS(
        title = "Coding Agents",
        keywords = listOf(
            "cli", "harness", "claude", "codex", "opencode", "gemini",
            "quick", "response", "composer", "prompt", "agent",
        ),
    ),
    ON_DEVICE_AI(
        title = "On-Device AI (Gemini Nano)",
        keywords = listOf(
            "aicore", "gemini", "nano", "model", "tier", "channel", "preview",
            "thinking", "warmup", "pre-warm", "polish", "shell", "download",
        ),
    ),
    TAILSCALE(
        title = "Tailscale",
        keywords = listOf(
            "tailnet", "headscale", "control", "url", "auth", "key", "peers",
            "vpn", "mesh", "tsnet", "hostname", "diagnostics",
        ),
    ),
    HOSTS_SECURITY(
        title = "Hosts & Security",
        keywords = listOf(
            "trusted", "known", "host", "fingerprint", "reachability", "probe",
            "mobile", "data", "wifi", "interval", "biometric", "app", "lock",
            "pin", "fingerprint", "privacy", "recents", "switcher", "screenshot",
            "flag_secure", "timeout",
        ),
    ),
    AUTOMATION(
        title = "Automation",
        keywords = listOf(
            "script", "intent", "token", "tasker", "macrodroid", "shortcut",
            "widget", "tile", "broadcast",
        ),
    ),
    BACKUP_RESTORE(
        title = "Backup & Restore",
        keywords = listOf(
            "export", "import", "backup", "restore", "passphrase", "encrypt",
            "migrate", "json", "config",
        ),
    ),
    SUPPORT(
        title = "Support",
        keywords = listOf(
            "sponsor", "shellwave", "supporter", "billing", "github", "donate",
        ),
    ),
    ABOUT(
        title = "About",
        keywords = listOf(
            "version", "update", "apk", "release", "notes", "automatic",
            "github", "releases", "source", "code", "licence", "license", "gplv3",
        ),
    ),
}

/**
 * Filters settings sections using whitespace-separated case-insensitive AND tokens across each
 * section's title and searchable option keywords.
 */
internal fun matchingSettingsSections(query: String): Set<SettingsSection> {
    val tokens = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return SettingsSection.entries.toSet()

    return SettingsSection.entries.filterTo(linkedSetOf()) { section ->
        val haystackTitle = section.title.lowercase()
        tokens.all { token ->
            haystackTitle.contains(token) || section.keywords.any { kw -> kw.contains(token) }
        }
    }
}
