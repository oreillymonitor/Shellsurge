package io.github.lordofpolls.shellwave.feature.session

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import io.github.lordofpolls.shellwave.core.prefs.AgentCli
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
    private const val TAG = "PromptRefiner"
    private var cachedModel: GenerativeModel? = null

    private fun getModel(): GenerativeModel {
        return cachedModel ?: Generation.getClient().also { cachedModel = it }
    }

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

    private suspend fun ensureNanoAvailable(onProgress: (String) -> Unit): Pair<GenerativeModel?, String?> {
        return try {
            val model = getModel()
            var status = model.checkStatus()

            if (status == FeatureStatus.DOWNLOADABLE) {
                onProgress("Downloading Gemini Nano from AICore…")
                var downloadSucceeded = false
                try {
                    model.download().collect { dStatus ->
                        when (dStatus) {
                            is DownloadStatus.DownloadProgress -> {
                                val mb = dStatus.totalBytesDownloaded / (1024 * 1024)
                                onProgress("Downloading Gemini Nano: ${mb}MB…")
                            }
                            is DownloadStatus.DownloadCompleted -> {
                                downloadSucceeded = true
                                onProgress("Gemini Nano ready. Generating…")
                            }
                            is DownloadStatus.DownloadFailed -> {
                                Log.w(TAG, "Download failed: $dStatus")
                            }
                            else -> {}
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Download flow error: ${e.message}")
                }
                if (downloadSucceeded) {
                    status = FeatureStatus.AVAILABLE
                }
            }

            if (status == FeatureStatus.AVAILABLE) {
                model to null
            } else {
                val reason = when (status) {
                    FeatureStatus.DOWNLOADING -> "Gemini Nano is downloading via AICore."
                    FeatureStatus.DOWNLOADABLE -> "Gemini Nano download required in AICore."
                    FeatureStatus.UNAVAILABLE -> "Gemini Nano unavailable. Enable AICore in Developer Options."
                    else -> "AICore status: $status"
                }
                Log.d(TAG, reason)
                null to reason
            }
        } catch (e: Throwable) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            Log.w(TAG, "Gemini Nano unavailable", e)
            null to err
        }
    }

    /**
     * Refines a raw voice prompt using on-device Gemini Nano if available,
     * or falls back to intelligent rule-based cleaning.
     */
    suspend fun refinePrompt(
        rawInput: String,
        activeAgent: AgentCli? = null,
        onProgress: (String) -> Unit = {},
    ): RefineResult = withContext(Dispatchers.IO) {
        val withMacros = applyVoiceMacros(rawInput, activeAgent).trim()
        if (withMacros.isBlank()) return@withContext RefineResult(withMacros, null)

        val (model, unavailableReason) = ensureNanoAvailable(onProgress)
        if (model != null) {
            try {
                onProgress("Refining with Gemini Nano…")
                val fullPrompt = """
                    You are a developer prompt refiner for an AI coding agent (like claude, opencode, codex, aider, or agy).
                    Transform the following user's spoken voice note into a crisp, direct, professional instruction.
                    Guidelines:
                    - Remove spoken filler words (uh, um, like, you know, so basically, hey).
                    - If there are multiple tasks or steps, organize them into a clean markdown bulleted list.
                    - Preserve all technical names, file paths, commands, flags, and slash commands (e.g. /goal, /plan).
                    - Output ONLY the refined prompt text without any introductory remarks or conversational preamble.

                    Voice Note:
                    $withMacros
                """.trimIndent()

                val response = model.generateContent(fullPrompt)
                val output = response.candidates.firstOrNull()?.text?.trim()
                if (!output.isNullOrBlank()) {
                    return@withContext RefineResult(output, "Polished with Gemini Nano (on-device)")
                }
            } catch (e: Throwable) {
                val err = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                Log.w(TAG, "Gemini Nano polish failed", e)
                return@withContext RefineResult(smartClean(withMacros), "AICore unavailable ($err). Used smart formatting.")
            }
        }

        val notice = unavailableReason?.let { reason ->
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
        onProgress: (String) -> Unit = {},
    ): RefineResult = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return@withContext RefineResult(trimmed, null)

        val (model, unavailableReason) = ensureNanoAvailable(onProgress)
        if (model != null) {
            try {
                onProgress("Translating to shell command…")
                val prompt = """
                    Convert the developer's request into a single safe, idiomatic Linux/POSIX shell command.
                    Output ONLY the raw runnable command on one line with no markdown code fences, no backticks, no explanation, and no leading $.

                    Request:
                    $trimmed
                """.trimIndent()

                val response = model.generateContent(prompt)
                val rawOutput = response.candidates.firstOrNull()?.text?.trim()
                val cmd = rawOutput?.let(::stripShellCommandFormatting)?.takeIf { it.isNotBlank() }
                if (cmd != null) {
                    return@withContext RefineResult(cmd, "Generated shell command with Gemini Nano")
                }
            } catch (e: Throwable) {
                val err = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                Log.w(TAG, "Shell command generation failed", e)
                return@withContext RefineResult(trimmed, "Shell command generation unavailable ($err).")
            }
        }

        val notice = unavailableReason ?: "Gemini Nano unavailable on this device."
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
