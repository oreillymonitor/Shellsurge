package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.terminal.AgentBarChoice
import io.github.lordofpolls.shellwave.terminal.AgentBarContext
import io.github.lordofpolls.shellwave.terminal.TerminalTextSanitizer

private val YES_NO_TOKEN_REGEX = Regex("""(?i)(\[y/n]|\(y/n\)|\[yes/no]|\(yes/no\))""")
private val PASSWORD_PROMPT_REGEX = Regex("""(?i)\b(?:password|passphrase|pin)\b[^.\n]*:\s*$""")
private val SPINNER_LINE_REGEX = Regex("""^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏◐◓◑◒\s]+$""")
private val RUNNING_STATUS_MARKERS = listOf(
    "esc to interrupt",
    "ctrl+c to interrupt",
    "thinking...",
    "thinking…",
    "working...",
    "working…",
    "generating...",
    "generating…",
    "executing...",
    "compiling...",
    "building...",
    "running...",
)

/**
 * Deterministically infers the best contextual [AgentBarContext] from the bottom lines of the
 * sanitized terminal screen buffer. Pure JVM logic with zero model latency.
 */
internal fun detectAgentBarContext(sanitizedTail: String): AgentBarContext {
    if (sanitizedTail.isBlank()) return AgentBarContext.Default

    val numberedOptions = AgentDigestModel.extractNumberedOptions(sanitizedTail)
    if (numberedOptions.isNotEmpty()) {
        return AgentBarContext.NumberedChoices(
            choices = numberedOptions.map { option ->
                AgentBarChoice(label = option.label, sendText = option.sendText)
            },
        )
    }

    val strippedLines = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeLast(4)

    for (line in strippedLines.takeLast(3).asReversed()) {
        if (PASSWORD_PROMPT_REGEX.containsMatchIn(line) ||
            line.contains("passphrase", ignoreCase = true)
        ) {
            return AgentBarContext.PasswordPrompt
        }
    }

    for (line in strippedLines.asReversed()) {
        val match = YES_NO_TOKEN_REGEX.find(line)
        if (match != null) {
            val token = match.value
            val defaultYes = when {
                token.contains("Y/n") || token.contains("Yes/no") -> true
                token.contains("y/N") || token.contains("yes/No") -> false
                else -> null
            }
            return AgentBarContext.YesNoPrompt(defaultYes = defaultYes)
        }
    }

    val rawTail = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeLast(6)

    val isRunning = rawTail.any { line ->
        SPINNER_LINE_REGEX.matches(line) ||
            RUNNING_STATUS_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }
    if (isRunning) return AgentBarContext.Running

    return AgentBarContext.Default
}
