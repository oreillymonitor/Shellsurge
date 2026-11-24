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

    internal val REFINE_SYSTEM_INSTRUCTION =
        "You are a text editor. Rewrite the user's draft into clear, concise English. " +
            "Remove spoken filler words, preserve every file path, identifier, flag, and slash command verbatim, " +
            "and do not invent new steps or details. Reply with only the rewritten message."

    internal val SHELL_CMD_SYSTEM_INSTRUCTION =
        "Translate the user's task into one safe POSIX/Linux shell command. " +
            "Reply with only the raw one-line command, with no markdown, backticks, explanation, or leading $."

    private val ECHO_LEAK_PHRASES = listOf(
        "you are a text editor",
        "rewrite the user's",
        "rewrite this rough draft",
        "remove spoken filler words",
        "preserve every file path",
        "keep every exact file path",
        "do not invent new steps",
        "do not add new files",
        "reply with only the rewritten",
        "output only the rewritten",
        "coding-agent prompt",
        "prompt for a coding agent",
        "translate the user's task into one safe",
        "reply with only the raw one-line command",
        "convert the user's request into a single safe",
        "no markdown, backticks, explanation",
        "no intro or labels",
    )

    private val ECHO_STOPWORDS = setOf(
        "the", "and", "for", "with", "not", "into", "from",
        "this", "that", "you", "are", "only", "all", "one",
    )

    private val TOKEN_REGEX = Regex("[a-z0-9_./-]+")

    internal fun buildRefineUserPrompt(draft: String): String =
        "Original:\n$draft\n\nRewritten:"

    internal fun buildShellCmdUserPrompt(request: String): String =
        "Task:\n$request\n\nCommand:"

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
        val labelMatch = Regex("(?im)^\\s*(?:rewritten|cleaned|edited|polished|refined(?:\\s+prompt)?|output)\\s*:\\s*")
            .findAll(cleaned)
            .lastOrNull()
        if (labelMatch != null) {
            val precedingLower = cleaned.substring(0, labelMatch.range.first).trimStart().lowercase()
            val looksEchoedBlock = precedingLower.contains("original:") ||
                precedingLower.startsWith("you are a text editor") ||
                precedingLower.startsWith("rewrite") ||
                precedingLower.startsWith("draft:")
            if (looksEchoedBlock) {
                cleaned = cleaned.substring(labelMatch.range.last + 1).trim()
            }
        }
        cleaned = cleaned
            .replace(Regex("(?is)</?(?:draft|output|prompt|response|original|rewritten)>"), "")
            .replace(
                Regex("(?i)^(?:output|refined(?:\\s+prompt)?|prompt|rewrite|rewritten|cleaned(?:\\s+message)?|edited(?:\\s+message)?|polished(?:\\s+message)?)\\s*:\\s*"),
                "",
            )
            .trim()
        if (cleaned.startsWith("\"\"\"") && cleaned.endsWith("\"\"\"") && cleaned.length >= 6) {
            cleaned = cleaned.substring(3, cleaned.length - 3).trim()
        } else if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned
    }

    internal fun isPromptEcho(
        candidate: String,
        originalInput: String,
        systemInstruction: String = REFINE_SYSTEM_INSTRUCTION,
    ): Boolean {
        val candidateLower = candidate.trim().lowercase()
        if (candidateLower.isBlank()) return false
        val originalLower = originalInput.trim().lowercase()
        val systemLower = systemInstruction.trim().lowercase()

        if (ECHO_LEAK_PHRASES.any { phrase -> phrase in candidateLower && phrase !in originalLower }) {
            return true
        }

        val systemClauses = systemLower
            .split(Regex("[.\\n]+"))
            .map { it.trim() }
            .filter { it.length >= 20 }
        if (systemClauses.any { clause -> clause in candidateLower && clause !in originalLower }) {
            return true
        }

        fun tokenize(text: String): List<String> =
            TOKEN_REGEX.findAll(text)
                .map { it.value }
                .filter { it.length >= 3 && it !in ECHO_STOPWORDS }
                .toList()

        val origTokens = tokenize(originalLower).toSet()
        val sysTokens = tokenize(systemLower).toSet()
        val leakedVocab = sysTokens - origTokens
        val candTokens = tokenize(candidateLower)
        if (candTokens.size >= 4) {
            val leakedCount = candTokens.count { it in leakedVocab }
            if (leakedCount >= 4 && leakedCount.toDouble() / candTokens.size >= 0.40) {
                return true
            }
        }
        return false
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
            userText = buildRefineUserPrompt(withMacros),
            temperature = 0.2f,
            topK = 16,
            maxOutputTokens = 256,
            allowThinking = false,
            onProgress = onProgress,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    val cleaned = cleanRefinedOutput(partial)
                    if (
                        cleaned.isNotBlank() &&
                        !AgentDigestModel.isNanoRefusal(cleaned) &&
                        !isPromptEcho(cleaned, withMacros, REFINE_SYSTEM_INSTRUCTION)
                    ) {
                        callback(cleaned)
                    }
                }
            },
        )

        val output = outcome.text?.let(::cleanRefinedOutput)?.takeIf {
            it.isNotBlank() &&
                !AgentDigestModel.isNanoRefusal(it) &&
                !isPromptEcho(it, withMacros, REFINE_SYSTEM_INSTRUCTION)
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

        val wasFiltered = !outcome.text.isNullOrBlank()
        val notice = if (wasFiltered) {
            "Nano output filtered; used smart formatting."
        } else {
            outcome.unavailableNotice?.let { reason ->
                if (reason.startsWith("Gemini") || reason.startsWith("AICore")) reason
                else "AICore unavailable ($reason). Used smart formatting."
            }
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
            userText = buildShellCmdUserPrompt(trimmed),
            temperature = 0.0f,
            topK = 5,
            maxOutputTokens = 96,
            allowThinking = true,
            onProgress = onProgress,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    val cleaned = stripShellCommandFormatting(partial)
                    if (
                        cleaned.isNotBlank() &&
                        !AgentDigestModel.isNanoRefusal(cleaned) &&
                        !isPromptEcho(cleaned, trimmed, SHELL_CMD_SYSTEM_INSTRUCTION)
                    ) {
                        callback(cleaned)
                    }
                }
            },
        )

        val cmd = outcome.text?.let(::stripShellCommandFormatting)?.takeIf {
            it.isNotBlank() &&
                !AgentDigestModel.isNanoRefusal(it) &&
                !isPromptEcho(it, trimmed, SHELL_CMD_SYSTEM_INSTRUCTION)
        }
        if (cmd != null) {
            val modelTag = outcome.baseModelName ?: "Gemini Nano"
            return@withContext RefineResult(cmd, "Generated shell command ($modelTag)")
        }

        val wasFiltered = !outcome.text.isNullOrBlank()
        val notice = if (wasFiltered) {
            "Nano output filtered; try rephrasing the command request."
        } else {
            outcome.unavailableNotice ?: "Gemini Nano unavailable on this device."
        }
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
