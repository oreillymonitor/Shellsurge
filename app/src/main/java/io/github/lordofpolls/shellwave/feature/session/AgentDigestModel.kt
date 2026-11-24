package io.github.lordofpolls.shellwave.feature.session

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
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
 * with an intelligent fallback analyzer for local command & agent outputs.
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

        // Try Gemini Nano first if available or downloadable on device
        try {
            val model = getModel()
            var status = model.checkStatus()

            if (status == FeatureStatus.DOWNLOADABLE) {
                try {
                    model.download().collect { dStatus ->
                        if (dStatus is DownloadStatus.DownloadCompleted) {
                            status = FeatureStatus.AVAILABLE
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Gemini Nano download error: ${e.message}")
                }
            }

            if (status == FeatureStatus.AVAILABLE) {
                val prompt = """
                    Brief the developer on this terminal status in 3 to 4 spoken sentences.
                    State what ran or finished, any errors or modified files, and what it's waiting on.
                    No greetings, intros, or meta-talk. Begin directly with the status.

                    Terminal:
                    $trimmed
                """.trimIndent()

                val response = model.generateContent(prompt)
                val rawResponse = response.candidates.firstOrNull()?.text?.trim()
                if (!rawResponse.isNullOrBlank()) {
                    val structured = extractStructuredResult(rawResponse)
                    return@withContext structured.copy(usedAi = true)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Gemini Nano analysis unavailable or failed", e)
        }

        // Fallback to intelligent rule-based extractor
        fallbackAnalyze(trimmed)
    }

    /**
     * Parses Gemini Nano's response into an AgentDigestResult, gracefully handling
     * JSON, markdown, or plain conversational speech.
     */
    internal fun extractStructuredResult(response: String): AgentDigestResult {
        // Attempt JSON extraction first if model generated JSON
        val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(response)?.value
        if (jsonMatch != null) {
            val parsed = runCatching {
                val json = JSONObject(jsonMatch)
                val spoken = json.optString("spoken", "").ifEmpty { json.optString("summary", "") }.trim()
                if (spoken.isNotEmpty()) {
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
                } else null
            }.getOrNull()
            if (parsed != null) return parsed
        }

        // Handle conversational text or markdown response
        var cleanText = response
            .replace(Regex("```[a-z]*"), "")
            .replace("```", "")
            .replace(Regex("[*#_]"), "")
            .trim()

        // Strip conversational SLM preambles if the model ever leaks them
        cleanText = cleanText.replace(
            Regex("^(Sure[,!]|Certainly[,!]|Okay[,!]|Here('s| is| are)|Based on|According to|In this summary|Summary:|Situation report:)[^:\n]*[:\n]\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        if (cleanText.startsWith("\"") && cleanText.endsWith("\"") && cleanText.length > 2) {
            cleanText = cleanText.substring(1, cleanText.length - 1).trim()
        }

        val yesNoRegex = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
        val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val questionLine = lines.lastOrNull { yesNoRegex.containsMatchIn(it) || it.endsWith("?") }
        val hasQuestion = questionLine != null
        val isError = cleanText.contains("error", ignoreCase = true) || cleanText.contains("failed", ignoreCase = true)

        return AgentDigestResult(
            spokenSummary = cleanText,
            hasQuestion = hasQuestion,
            question = questionLine,
            quickOptions = if (hasQuestion) listOf("Yes", "No") else emptyList(),
            isError = isError,
        )
    }

    internal fun fallbackAnalyze(output: String): AgentDigestResult {
        val allLines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) {
            return AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        // Identify shell prompt lines (e.g., user@host:~$ or ending with $, #, >)
        val promptRegex = Regex("^[\\w.-]+@[\\w.-]+:.*[$#%]\\s*$|^[$#>❯%]\\s*$")
        val meaningfulLines = allLines.filter { line ->
            !promptRegex.matches(line) && !line.endsWith("$") && !line.endsWith("#") && !line.endsWith(">")
        }

        val analysisPool = if (meaningfulLines.isNotEmpty()) meaningfulLines else allLines
        val lastFew = analysisPool.takeLast(12)

        // 1. Detect pending prompt questions (e.g. [y/N], (y/n), or trailing question)
        val yesNoRegex = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
        val questionLine = lastFew.lastOrNull { line ->
            yesNoRegex.containsMatchIn(line) || line.endsWith("?") || line.contains("confirm", ignoreCase = true)
        }

        if (questionLine != null) {
            val cleanQuestion = yesNoRegex.replace(questionLine, "").trim().removeSuffix("?") + "?"
            return AgentDigestResult(
                spokenSummary = "The agent has completed its current step and is waiting for your confirmation: $cleanQuestion. Please review the proposal before proceeding. Would you like to proceed?",
                hasQuestion = true,
                question = cleanQuestion,
                quickOptions = listOf("Yes", "No"),
                isError = false,
                usedAi = false,
            )
        }

        // 2. Detect error or failure
        val failureLine = lastFew.lastOrNull { line ->
            line.contains("BUILD FAILED", ignoreCase = true) ||
                line.contains("FAILURES!!!", ignoreCase = true) ||
                line.contains("FAILURE:", ignoreCase = true) ||
                line.contains("error:", ignoreCase = true) ||
                line.contains("Exception:", ignoreCase = true) ||
                line.contains("fatal:", ignoreCase = true)
        }
        if (failureLine != null) {
            val cleanError = failureLine.removePrefix("e: ").trim()
            return AgentDigestResult(
                spokenSummary = "The command execution failed with an error: $cleanError. The terminal reported a failure during this task. Please inspect the recent error trace or run with debug flags to troubleshoot.",
                hasQuestion = false,
                isError = true,
                usedAi = false,
            )
        }

        // 3. Detect build success or completed task
        val successLine = lastFew.lastOrNull { line ->
            line.contains("BUILD SUCCESSFUL", ignoreCase = true) ||
                line.contains("Tests run:", ignoreCase = true) ||
                line.contains("passed in", ignoreCase = true) ||
                line.contains("up to date", ignoreCase = true) ||
                line.contains("done", ignoreCase = true) ||
                line.contains("completed", ignoreCase = true) ||
                line.contains("committed", ignoreCase = true) ||
                line.contains("pushed", ignoreCase = true)
        }
        if (successLine != null) {
            return AgentDigestResult(
                spokenSummary = "Agent completed task successfully: $successLine. All build steps and operations finished cleanly without errors. The terminal is back at the shell prompt ready for your next instruction.",
                hasQuestion = false,
                isError = false,
                usedAi = false,
            )
        }

        // 4. Summarize the last meaningful command or agent outputs
        if (meaningfulLines.isNotEmpty()) {
            val summaryText = meaningfulLines.takeLast(2).joinToString(". ")
            return AgentDigestResult(
                spokenSummary = "Latest agent activity recorded: $summaryText. The background operation has concluded its phase. The session is active and waiting for your next input.",
                hasQuestion = false,
                isError = false,
                usedAi = false,
            )
        }

        // True idle prompt
        val lastPrompt = allLines.lastOrNull() ?: "idle"
        return AgentDigestResult(
            spokenSummary = "Terminal is idle at the command prompt. No background processes or agent actions are currently running. Enter a command or launch an agent to begin.",
            hasQuestion = false,
            isError = false,
            usedAi = false,
        )
    }
}
