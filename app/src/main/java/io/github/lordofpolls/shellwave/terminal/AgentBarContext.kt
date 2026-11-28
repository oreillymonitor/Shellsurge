package io.github.lordofpolls.shellwave.terminal

/**
 * Compact parsed action button surfaced on Row 1 of the Agent Deck when the terminal displays an
 * interactive numbered prompt or approval selector.
 */
data class AgentBarChoice(
    val label: String,
    val sendText: String,
)

/**
 * High-level interactive state of the bottom lines of the active terminal, used to adapt Row 1 of
 * the [KeyBar] Agent Deck without invoking an LLM.
 */
sealed interface AgentBarContext {
    data object Default : AgentBarContext
    data class NumberedChoices(val choices: List<AgentBarChoice>) : AgentBarContext
    data class YesNoPrompt(val defaultYes: Boolean? = null) : AgentBarContext
    data object PasswordPrompt : AgentBarContext
    data object Running : AgentBarContext
}
