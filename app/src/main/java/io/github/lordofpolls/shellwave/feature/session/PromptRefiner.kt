package io.github.lordofpolls.shellwave.feature.session

import android.util.Log
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device prompt refiner using Gemini Nano (via Android AICore / ML Kit GenAI)
 * on Pixel 9 and compatible devices, with graceful rule-based fallback.
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
    suspend fun refinePrompt(rawInput: String): String = withContext(Dispatchers.IO) {
        val withMacros = applyVoiceMacros(rawInput).trim()
        if (withMacros.isBlank()) return@withContext withMacros

        try {
            val model = getModel()
            val status = model.checkStatus()
            if (status == FeatureStatus.AVAILABLE) {
                val systemPrompt = """
                    You are a developer prompt refiner for an AI coding agent (like antigravity-cli or opencode).
                    Transform the user's spoken voice note into a crisp, direct, professional instruction.
                    Guidelines:
                    - Remove spoken filler words (uh, um, like, you know, so basically, hey).
                    - If there are multiple tasks or steps, organize them into a clean markdown bulleted list.
                    - Preserve all technical names, file paths, commands, flags, and slash commands (e.g. /goal, /plan).
                    - Output ONLY the refined prompt text without any introductory remarks or conversational preamble.
                """.trimIndent()

                val request = GenerateContentRequest.Builder()
                    .setSystemInstruction(SystemInstruction(systemPrompt))
                    .newText(withMacros)
                    .build()

                val response = model.generateContent(request)
                val output = response.candidates.firstOrNull()?.text?.trim()
                if (!output.isNullOrBlank()) {
                    return@withContext output
                }
            } else {
                Log.d(TAG, "Gemini Nano status: $status, falling back to rule-based polish")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Gemini Nano polish failed, falling back to rule-based: ${e.message}")
        }

        // Rule-based fallback
        fallbackClean(withMacros)
    }

    private fun fallbackClean(input: String): String {
        var text = input
        val fillers = listOf(
            Regex("(?i)\\b(um|uh|er|ah)\\b[,\\s]*"),
            Regex("(?i)\\b(you know|so basically|like I said|I mean)\\b[,\\s]*"),
        )
        for (filler in fillers) {
            text = filler.replace(text, "")
        }
        // Capitalize sentences
        return text.split("\n").joinToString("\n") { line ->
            line.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.trim()
    }
}
