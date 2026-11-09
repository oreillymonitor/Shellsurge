package io.github.lordofpolls.shellwave.feature.session

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AgentDigestResult(
    val spokenSummary: String,
    val hasQuestion: Boolean = false,
    val question: String? = null,
    val quickOptions: List<String> = emptyList(),
    val isError: Boolean = false,
    val usedAi: Boolean = false,
)

/**
 * On-device terminal output digest engine using Gemini Nano via Android AICore / ML Kit GenAI,
 * with an instant intelligent fallback regex parser.
 */
internal object AgentDigestModel {
    private const val TAG = "AgentDigestModel"
    private var cachedModel: GenerativeModel? = null

    private fun getModel(): GenerativeModel {
        return cachedModel ?: Generation.getClient().also { cachedModel = it }
    }

    suspend fun analyzeTerminalTail(sanitizedOutput: String): AgentDigestResult = withContext(Dispatchers.IO) {
        val trimmed = sanitizedOutput.trim()
        if (trimmed.isBlank()) {
            return@withContext AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        // Try Gemini Nano first if available on device
        try {
            val model = getModel()
            val status = runCatching { model.checkStatus() }.getOrNull()
            if (status == FeatureStatus.AVAILABLE) {
                val prompt = """
                    You are an expert developer assistant reading terminal output from an AI coding agent (e.g., Claude Code, Antigravity, Aider).
                    Analyze what the agent did and what it needs.

                    Instructions:
                    1. Summary: Provide 1 to 2 conversational, clear sentences summarizing what the agent accomplished or the current status.
                    2. Novelty / Self-Correction Rule: If and only if the agent encountered an error it self-corrected, an unexpected test failure it fixed, or a novel workaround, add exactly 1 additional sentence describing that. Otherwise omit.
                    3. If the agent is waiting on user input (e.g. [y/N], confirmation, or question), set has_question to true, extract the question, and provide 2-3 quick options (e.g. ["Yes", "No"]).

                    Respond ONLY in valid JSON matching this schema:
                    {
                      "spoken": "1 to 2 sentences summarizing what happened. Optional 3rd sentence if self-corrected or novel.",
                      "has_question": true,
                      "question": "Question text or null",
                      "quick_options": ["Yes", "No"],
                      "is_error": false
                    }

                    Terminal Output Tail:
                    $trimmed
                """.trimIndent()

                val response = model.generateContent(prompt)
                val text = response.candidates.firstOrNull()?.text?.trim()
                if (!text.isNullOrBlank()) {
                    val parsed = parseJsonResponse(text)
                    if (parsed != null) {
                        return@withContext parsed.copy(usedAi = true)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Gemini Nano analysis unavailable or failed: ${e.message}")
        }

        // Fallback to local rule-based extractor
        fallbackAnalyze(trimmed)
    }

    private fun parseJsonResponse(jsonStr: String): AgentDigestResult? {
        val cleaned = jsonStr.replace("```json", "").replace("```", "").trim()
        return runCatching {
            val json = JSONObject(cleaned)
            val spoken = json.optString("spoken", "").trim()
            if (spoken.isEmpty()) return null
            val hasQuestion = json.optBoolean("has_question", false)
            val question = json.optString("question", "").takeIf { it.isNotBlank() }
            val quickArray = json.optJSONArray("quick_options")
            val quickOptions = mutableListOf<String>()
            if (quickArray != null) {
                for (i in 0 until quickArray.length()) {
                    val opt = quickArray.optString(i, "").trim()
                    if (opt.isNotEmpty()) quickOptions.add(opt)
                }
            }
            val isError = json.optBoolean("is_error", false)
            AgentDigestResult(
                spokenSummary = spoken,
                hasQuestion = hasQuestion,
                question = question,
                quickOptions = if (hasQuestion && quickOptions.isEmpty()) listOf("Yes", "No") else quickOptions,
                isError = isError,
            )
        }.getOrNull()
    }

    private fun fallbackAnalyze(output: String): AgentDigestResult {
        val lines = output.lines().filter { it.isNotBlank() }
        val lastFew = lines.takeLast(15)

        // 1. Detect pending prompt questions
        val yesNoRegex = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
        val questionLine = lastFew.lastOrNull { line ->
            yesNoRegex.containsMatchIn(line) || line.trimEnd().endsWith("?")
        }

        if (questionLine != null) {
            val cleanQuestion = yesNoRegex.replace(questionLine, "").trim().removeSuffix("?") + "?"
            return AgentDigestResult(
                spokenSummary = "Agent is waiting for your response: $cleanQuestion",
                hasQuestion = true,
                question = cleanQuestion,
                quickOptions = listOf("Yes", "No"),
                isError = false,
                usedAi = false,
            )
        }

        // 2. Detect error / failure
        val failureLine = lastFew.lastOrNull { line ->
            line.contains("BUILD FAILED", ignoreCase = true) ||
                line.contains("FAILURES!!!", ignoreCase = true) ||
                line.contains("FAILURE:", ignoreCase = true) ||
                line.startsWith("Error:", ignoreCase = true)
        }
        if (failureLine != null) {
            return AgentDigestResult(
                spokenSummary = "Command failed with an error. Check the terminal output for details.",
                hasQuestion = false,
                isError = true,
                usedAi = false,
            )
        }

        // 3. Detect build success or completed task
        val successLine = lastFew.lastOrNull { line ->
            line.contains("BUILD SUCCESSFUL", ignoreCase = true) ||
                line.contains("Tests run:", ignoreCase = true) ||
                line.contains("done", ignoreCase = true)
        }
        if (successLine != null) {
            return AgentDigestResult(
                spokenSummary = "Agent finished task successfully. All checks passed.",
                hasQuestion = false,
                isError = false,
                usedAi = false,
            )
        }

        // General default
        val lastLine = lastFew.lastOrNull() ?: "idle"
        return AgentDigestResult(
            spokenSummary = "Agent is idle. Current prompt: $lastLine",
            hasQuestion = false,
            isError = false,
            usedAi = false,
        )
    }
}
