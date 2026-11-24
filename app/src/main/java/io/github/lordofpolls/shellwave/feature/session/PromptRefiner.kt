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

    internal val REFINE_SYSTEM_INSTRUCTION = """
        Rewrite the user's rough draft into a clear, concise prompt for a coding agent.
        Remove spoken filler words (uh, um, like, hey, basically, you know).
        Keep every exact file path, identifier, flag, and slash command from the draft.
        Do not add new files, errors, or steps that were not mentioned.
        Use short bullet points only if the draft has multiple separate tasks.
        Output only the rewritten prompt text with no intro or labels.
    """.trimIndent()

    internal val SHELL_CMD_SYSTEM_INSTRUCTION = """
        Convert the user's request into a single safe, idiomatic POSIX/Linux shell command.
        Output only the raw one-line command with no markdown code fences, backticks, explanation, labels, or leading $.
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

    internal fun cleanRefinedOutput(raw: String): String {
        var cleaned = raw.trim()
        cleaned = cleaned
            .replace(Regex("(?is)</?(?:draft|output|prompt|response)>"), "")
            .replace(Regex("(?i)^(?:output|refined(?:\\s+prompt)?|prompt|rewrite)\\s*:\\s*"), "")
            .trim()
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned
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
            userText = "Rewrite this rough draft into a clear coding-agent prompt:\n\nDraft:\n$withMacros",
            temperature = 0.1f,
            topK = 10,
            maxOutputTokens = 256,
            allowThinking = false,
            onProgress = onProgress,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    val cleaned = cleanRefinedOutput(partial)
                    if (cleaned.isNotBlank() && !AgentDigestModel.isNanoRefusal(cleaned)) {
                        callback(cleaned)
                    }
                }
            },
        )

        val output = outcome.text?.let(::cleanRefinedOutput)?.takeIf {
            it.isNotBlank() && !AgentDigestModel.isNanoRefusal(it)
        }
        if (output != null) {
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
            .replace(Regex("(?is)</?(?:request|command|output)>"), "")
            .replace(Regex("(?i)^(?:command|output)\\s*:\\s*"), "")
            .trim()
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
            userText = "Convert this request into one POSIX/Linux shell command:\n\nRequest:\n$trimmed",
            temperature = 0.0f,
            topK = 5,
            maxOutputTokens = 96,
            allowThinking = true,
            onProgress = onProgress,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    val cleaned = stripShellCommandFormatting(partial)
                    if (cleaned.isNotBlank() && !AgentDigestModel.isNanoRefusal(cleaned)) {
                        callback(cleaned)
                    }
                }
            },
        )

        val cmd = outcome.text?.let(::stripShellCommandFormatting)?.takeIf {
            it.isNotBlank() && !AgentDigestModel.isNanoRefusal(it)
        }
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
