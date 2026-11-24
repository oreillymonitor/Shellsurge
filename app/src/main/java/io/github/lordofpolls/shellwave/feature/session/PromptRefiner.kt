package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import io.github.lordofpolls.shellwave.core.prefs.NanoModelPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RefineResult(
    val text: String,
    val statusNotice: String?,
)

/**
 * On-device prompt refiner using Gemini Nano (via Android AICore / ML Kit GenAI)
 * on Pixel 9 and compatible devices, with intelligent rule-based formatting fallback.
 */
internal object PromptRefiner {

    private val REFINE_SYSTEM_INSTRUCTION = """
        You refine spoken voice notes from developers into crisp, direct prompts for AI coding agents (claude, opencode, codex, aider, agy).
        Rules:
        - Remove spoken fillers (um, uh, like, hey, basically, please, you know).
        - Preserve every file path, symbol name, CLI flag, and slash command (/plan, /diff, /model, /models, /goal).
        - Use a concise markdown bulleted list when there are multiple distinct tasks.
        - Output ONLY the refined prompt with zero greeting or explanation.

        Example 1:
        Input: hey um can you check why SessionManager is reconnecting twice and also add a unit test for it
        Output:
        Investigate and fix double reconnects in SessionManager:
        - Identify why reconnects trigger twice on disconnect.
        - Add a unit test verifying single reconnect behavior.

        Example 2:
        Input: slash plan refactor PromptRefiner so basically we reuse one client
        Output:
        /plan Refactor PromptRefiner to reuse a single client instance.
    """.trimIndent()

    private val SHELL_CMD_SYSTEM_INSTRUCTION = """
        Convert a developer's natural-language request into a single safe, idiomatic POSIX/Linux shell command.
        Output ONLY the raw one-line command with no markdown code fences, no backticks, no explanation, and no leading $.

        Examples:
        Request: find all log files modified in the last 2 days and delete them
        Command: find . -type f -name '*.log' -mtime -2 -delete

        Request: show processes listening on tcp ports
        Command: ss -tulpn

        Request: show last 5 git commits on one line
        Command: git log -n 5 --oneline
    """.trimIndent()

    /**
     * Converts common spoken phrases into markdown structure and agent-aware CLI slash commands.
     */
    fun applyVoiceMacros(text: String, activeAgent: AgentCli? = null): String {
        val modelSlash = if (activeAgent == AgentCli.OPENCODE) "/models" else "/model"
        var result = text
        val macros = listOf(
            Regex("(?i)\\b(slash\\s*models?)\\b") to modelSlash,
            Regex("(?i)\\b(slash\\s*usage)\\b") to "/usage",
            Regex("(?i)\\b(slash\\s*goal)\\b") to "/goal",
            Regex("(?i)\\b(slash\\s*plan)\\b") to "/plan",
            Regex("(?i)\\b(slash\\s*compact)\\b") to "/compact",
            Regex("(?i)\\b(slash\\s*clear)\\b") to "/clear",
            Regex("(?i)\\b(slash\\s*diff)\\b") to "/diff",
            Regex("(?i)\\b(slash\\s*review)\\b") to "/review",
            Regex("(?i)\\b(slash\\s*undo)\\b") to "/undo",
            Regex("(?i)\\b(slash\\s*cost)\\b") to "/cost",
            Regex("(?i)\\b(slash\\s*resume)\\b") to "/resume",
            Regex("(?i)\\b(slash\\s*init)\\b") to "/init",
            Regex("(?i)\\b(slash\\s*status)\\b") to "/status",
            Regex("(?i)\\b(slash\\s*help)\\b") to "/help",
            Regex("(?i)\\b(slash\\s*browser)\\b") to "/browser",
            Regex("(?i)\\b(slash\\s*grill\\s*me)\\b") to "/grill-me",
            Regex("(?i)\\b(slash\\s*boost)\\b") to "/boost",
            Regex("(?i)\\b(slash\\s*learn)\\b") to "/learn",
            Regex("(?i)\\b(new\\s*paragraph)\\b") to "\n\n",
            Regex("(?i)\\b(new\\s*line)\\b") to "\n",
            Regex("(?i)\\b(bullet\\s*point|bullet)\\b") to "\n- ",
        )
        for ((regex, replacement) in macros) {
            result = regex.replace(result, replacement)
        }
        return result
    }

    /**
     * Refines a raw voice prompt using on-device Gemini Nano if available,
     * or falls back to intelligent rule-based cleaning.
     */
    suspend fun refinePrompt(
        rawInput: String,
        activeAgent: AgentCli? = null,
        context: Context? = null,
        onProgress: (String) -> Unit = {},
        onPartialText: ((String) -> Unit)? = null,
    ): RefineResult = withContext(Dispatchers.IO) {
        val withMacros = applyVoiceMacros(rawInput, activeAgent).trim()
        if (withMacros.isBlank()) return@withContext RefineResult(withMacros, null)

        onProgress("Refining with Gemini Nano…")
        val outcome = NanoClientManager.generateText(
            context = context,
            systemInstruction = REFINE_SYSTEM_INSTRUCTION,
            userText = "Voice Note:\n$withMacros",
            temperature = 0.2f,
            topK = 16,
            maxOutputTokens = 256,
            allowThinking = true,
            onProgress = onProgress,
            onPartialText = onPartialText,
        )

        val output = outcome.text?.trim()
        if (!output.isNullOrBlank()) {
            val modelTag = if (context != null) {
                formatModelLabel(
                    outcome.baseModelName,
                    NanoModelPreferences.getModelTier(context),
                    NanoModelPreferences.getReleaseChannel(context),
                )
            } else {
                outcome.baseModelName ?: "Gemini Nano"
            }
            return@withContext RefineResult(output, "Polished with $modelTag")
        }

        val notice = outcome.unavailableNotice?.let { reason ->
            if (reason.startsWith("Gemini") || reason.startsWith("AICore")) reason
            else "AICore unavailable ($reason). Used smart formatting."
        }
        RefineResult(smartClean(withMacros), notice)
    }

    internal fun stripShellCommandFormatting(raw: String): String {
        var cleaned = raw.trim()
        // Drop enclosing fenced code block lines if Nano emits ```bash ... ```
        cleaned = cleaned
            .replace(Regex("^```[a-zA-Z0-9_-]*\\s*\\n?"), "")
            .replace(Regex("\\n?```\\s*$"), "")
            .trim()
        if (cleaned.startsWith("`") && cleaned.endsWith("`") && cleaned.length >= 2 && cleaned.count { it == '`' } == 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        // Strip leading shell prompt prefix ($ or #) emitted in examples
        cleaned = cleaned.replaceFirst(Regex("^(\\$|#)\\s+"), "")
        return cleaned.trim()
    }

    /**
     * Converts a natural-language task description into a single POSIX/Linux shell command
     * using on-device Gemini Nano.
     */
    suspend fun generateShellCommand(
        rawInput: String,
        context: Context? = null,
        onProgress: (String) -> Unit = {},
        onPartialText: ((String) -> Unit)? = null,
    ): RefineResult = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return@withContext RefineResult(trimmed, null)

        onProgress("Translating to shell command…")
        val outcome = NanoClientManager.generateText(
            context = context,
            systemInstruction = SHELL_CMD_SYSTEM_INSTRUCTION,
            userText = "Request: $trimmed\nCommand:",
            temperature = 0.0f,
            topK = 5,
            maxOutputTokens = 96,
            allowThinking = true,
            onProgress = onProgress,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    val cleaned = stripShellCommandFormatting(partial)
                    if (cleaned.isNotBlank()) callback(cleaned)
                }
            },
        )

        val cmd = outcome.text?.let(::stripShellCommandFormatting)?.takeIf { it.isNotBlank() }
        if (cmd != null) {
            val modelTag = outcome.baseModelName ?: "Gemini Nano"
            return@withContext RefineResult(cmd, "Generated shell command ($modelTag)")
        }

        val notice = outcome.unavailableNotice ?: "Gemini Nano unavailable on this device."
        RefineResult(trimmed, notice)
    }

    fun smartClean(input: String): String {
        var text = input

        // 1. Remove introductory conversational filler phrases
        val introFillers = listOf(
            Regex("(?i)^\\s*(?:(?:hey|so|basically|um|uh|can you|could you|please|i want to|i need to|what if we|how about we)[,\\s]+)+"),
            Regex("(?i)\\b(you know|like I said|I mean|as well|sort of|kind of)\\b[,\\s]*"),
            Regex("(?i)\\b(um|uh|er|ah)\\b[,\\s]*"),
        )
        for (filler in introFillers) {
            text = filler.replace(text, "")
        }

        // 2. If user strung multiple steps with "and also", "and then", "also make sure":
        // convert them into clean bullet points if not already bulleted
        if (!text.contains("\n-") && !text.contains("\n*")) {
            val stepDelimiters = Regex("(?i)\\s+(and also|and then|and please|also make sure to|also make sure that|also)\\s+")
            if (stepDelimiters.containsMatchIn(text)) {
                val parts = text.split(stepDelimiters).map { it.trim().trimEnd(',', ';') }.filter { it.isNotBlank() }
                if (parts.size > 1) {
                    val header = parts.first().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val bullets = parts.drop(1).joinToString("\n") { step ->
                        "- " + step.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                    return "$header:\n$bullets"
                }
            }
        }

        // 3. Sentence capitalization
        return text.split("\n").joinToString("\n") { line ->
            line.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.trim()
    }
}
