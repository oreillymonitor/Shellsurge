package io.github.lordofpolls.shellwave.feature.session

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
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
     * Converts common spoken phrases into markdown structure and CLI slash commands.
     */
    fun applyVoiceMacros(text: String): String {
        var result = text
        val macros = listOf(
            Regex("(?i)\\b(slash\\s*models?)\\b") to "/models",
            Regex("(?i)\\b(slash\\s*usage)\\b") to "/usage",
            Regex("(?i)\\b(slash\\s*goal)\\b") to "/goal",
            Regex("(?i)\\b(slash\\s*plan)\\b") to "/plan",
            Regex("(?i)\\b(slash\\s*compact)\\b") to "/compact",
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
        onProgress: (String) -> Unit = {},
    ): RefineResult = withContext(Dispatchers.IO) {
        val withMacros = applyVoiceMacros(rawInput).trim()
        if (withMacros.isBlank()) return@withContext RefineResult(withMacros, null)

        var nanoNotice: String? = null
        try {
            val model = getModel()
            var status = runCatching { model.checkStatus() }.getOrNull()

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
                onProgress("Refining with Gemini Nano…")
                val fullPrompt = """
                    You are a developer prompt refiner for an AI coding agent (like antigravity-cli or opencode).
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
            } else {
                val reason = when (status) {
                    FeatureStatus.DOWNLOADING -> "Gemini Nano is downloading via AICore."
                    FeatureStatus.DOWNLOADABLE -> "Gemini Nano download required in AICore."
                    FeatureStatus.UNAVAILABLE -> "Gemini Nano unavailable. Enable AICore in Developer Options."
                    null -> "AICore model service uninitialized on device."
                    else -> "AICore status: $status"
                }
                Log.d(TAG, reason)
                nanoNotice = reason
            }
        } catch (e: Throwable) {
            val err = e.message ?: "AICore uninitialized"
            Log.w(TAG, "Gemini Nano polish failed: $err")
            nanoNotice = "AICore unavailable ($err). Used smart formatting."
        }

        // Smart format fallback
        val cleaned = smartClean(withMacros)
        RefineResult(cleaned, nanoNotice)
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
