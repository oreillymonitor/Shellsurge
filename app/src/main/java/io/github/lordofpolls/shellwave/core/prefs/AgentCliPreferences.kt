package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val KEY_ENABLED_AGENT_CLIS = "enabled_agent_clis"
private const val KEY_SELECTED_AGENT_CLI = "selected_agent_cli"

data class AgentLaunchCommand(
    val label: String,
    val command: String,
    val isResume: Boolean = false,
)

enum class AgentCli(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val launchCommands: List<AgentLaunchCommand>,
    val slashCommands: List<String>,
    val highlightedCommands: Set<String>,
) {
    CLAUDE(
        id = "claude",
        displayName = "Claude Code",
        shortName = "claude",
        description = "Anthropic's terminal coding agent (claude, claude -c)",
        launchCommands = listOf(
            AgentLaunchCommand(label = "claude", command = "claude"),
            AgentLaunchCommand(label = "claude -c", command = "claude -c", isResume = true),
        ),
        slashCommands = listOf(
            "/compact",
            "/clear",
            "/model",
            "/cost",
            "/review",
            "/init",
            "/plan",
            "/resume",
            "/memory",
            "/mcp",
            "/doctor",
            "/help",
        ),
        highlightedCommands = setOf("/compact", "/model", "/cost", "/plan"),
    ),
    OPENCODE(
        id = "opencode",
        displayName = "OpenCode",
        shortName = "opencode",
        description = "Open-source terminal coding agent (opencode)",
        launchCommands = listOf(
            AgentLaunchCommand(label = "opencode", command = "opencode"),
        ),
        slashCommands = listOf(
            "/models",
            "/compact",
            "/sessions",
            "/new",
            "/undo",
            "/redo",
            "/review",
            "/init",
            "/share",
            "/mcps",
            "/connect",
            "/help",
            "/clear",
        ),
        highlightedCommands = setOf("/models", "/compact", "/sessions", "/new"),
    ),
    CODEX(
        id = "codex",
        displayName = "Codex CLI",
        shortName = "codex",
        description = "OpenAI's terminal coding agent (codex)",
        launchCommands = listOf(
            AgentLaunchCommand(label = "codex", command = "codex"),
        ),
        slashCommands = listOf(
            "/model",
            "/approvals",
            "/review",
            "/diff",
            "/compact",
            "/new",
            "/status",
            "/init",
            "/mcp",
            "/help",
        ),
        highlightedCommands = setOf("/model", "/approvals", "/diff", "/compact"),
    ),
    AIDER(
        id = "aider",
        displayName = "Aider",
        shortName = "aider",
        description = "AI pair programming in your terminal (aider)",
        launchCommands = listOf(
            AgentLaunchCommand(label = "aider", command = "aider"),
        ),
        slashCommands = listOf(
            "/add",
            "/drop",
            "/diff",
            "/undo",
            "/architect",
            "/ask",
            "/code",
            "/test",
            "/model",
            "/clear",
            "/tokens",
            "/help",
        ),
        highlightedCommands = setOf("/add", "/diff", "/undo", "/model"),
    ),
    AGY(
        id = "agy",
        displayName = "Antigravity CLI",
        shortName = "agy",
        description = "Antigravity agent CLI (agy, agy -c)",
        launchCommands = listOf(
            AgentLaunchCommand(label = "agy", command = "agy"),
            AgentLaunchCommand(label = "agy -c", command = "agy -c", isResume = true),
        ),
        slashCommands = listOf(
            "/model",
            "/usage",
            "/plan",
            "/goal",
            "/compact",
            "/diff",
            "/grill-me",
            "/boost",
            "/teamwork",
            "/browser",
            "/learn",
            "/resume",
            "/btw",
            "/help",
        ),
        highlightedCommands = setOf("/model", "/usage", "/plan", "/goal"),
    ),
}

/**
 * Stores which AI coding agent CLIs the user toggled on in Settings and which one is active in
 * the Quick Response sheet / Prompt Composer. Uses [sharedPrefs] so [rememberPrefState] observes
 * changes immediately.
 */
object AgentCliPreferences {
    val DEFAULT_ENABLED: List<AgentCli> = listOf(AgentCli.CLAUDE, AgentCli.OPENCODE)

    fun getEnabledAgents(context: Context): List<AgentCli> {
        val prefs = sharedPrefs(context)
        if (!prefs.contains(KEY_ENABLED_AGENT_CLIS)) return DEFAULT_ENABLED
        val raw = prefs.getString(KEY_ENABLED_AGENT_CLIS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return AgentCli.entries.filter { it.id in ids }
    }

    fun isAgentEnabled(context: Context, agent: AgentCli): Boolean =
        agent in getEnabledAgents(context)

    fun setAgentEnabled(context: Context, agent: AgentCli, enabled: Boolean) {
        val current = getEnabledAgents(context).toMutableSet()
        if (enabled) current.add(agent) else current.remove(agent)
        val ordered = AgentCli.entries.filter { it in current }
        val prefs = sharedPrefs(context)
        val currentSelectedId = prefs.getString(KEY_SELECTED_AGENT_CLI, null)
        prefs.edit {
            putString(KEY_ENABLED_AGENT_CLIS, ordered.joinToString(",") { it.id })
            val selectedStillEnabled = ordered.any { it.id == currentSelectedId }
            if (!selectedStillEnabled) {
                val fallback = ordered.firstOrNull()
                if (fallback != null) {
                    putString(KEY_SELECTED_AGENT_CLI, fallback.id)
                } else {
                    remove(KEY_SELECTED_AGENT_CLI)
                }
            }
        }
    }

    fun getSelectedAgent(context: Context): AgentCli? {
        val enabled = getEnabledAgents(context)
        if (enabled.isEmpty()) return null
        val storedId = sharedPrefs(context).getString(KEY_SELECTED_AGENT_CLI, null)
        return enabled.firstOrNull { it.id == storedId } ?: enabled.first()
    }

    fun setSelectedAgent(context: Context, agent: AgentCli) {
        sharedPrefs(context).edit {
            putString(KEY_SELECTED_AGENT_CLI, agent.id)
        }
    }
}
