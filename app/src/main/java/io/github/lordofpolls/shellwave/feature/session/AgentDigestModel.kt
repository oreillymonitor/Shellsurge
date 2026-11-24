package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DigestQuickAction(
    val label: String,
    val sendText: String,
)

data class AgentDigestResult(
    val spokenSummary: String,
    val hasQuestion: Boolean = false,
    val question: String? = null,
    val quickOptions: List<String> = emptyList(),
    val quickActions: List<DigestQuickAction> = emptyList(),
    val isError: Boolean = false,
    val usedAi: Boolean = false,
)

/**
 * On-device terminal output digest engine using Gemini Nano via Android AICore / ML Kit GenAI,
 * with an intelligent fallback analyzer for local command & agent outputs.
 */
internal object AgentDigestModel {
    internal val DIGEST_SYSTEM_INSTRUCTION = """
        Summarize ONLY the terminal output inside <terminal>...</terminal> in 2 to 3 crisp spoken sentences.
        State what actually ran, succeeded, or failed, any files or errors explicitly shown in <terminal>, and whether it is waiting on confirmation.
        Never invent commands, files, or test results not shown in <terminal>.
        Use zero greetings, intros, markdown formatting, labels, or XML tags.
    """.trimIndent()

    suspend fun analyzeTerminalTail(
        sanitizedOutput: String,
        context: Context? = null,
        onPartialText: ((String) -> Unit)? = null,
    ): AgentDigestResult = withContext(Dispatchers.IO) {
        val trimmed = sanitizedOutput.trim()
        if (trimmed.isBlank()) {
            return@withContext AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        val detectedActions = extractNumberedOptions(trimmed)

        val outcome = NanoClientManager.generateText(
            context = context,
            systemInstruction = DIGEST_SYSTEM_INSTRUCTION,
            userText = "<terminal>\n$trimmed\n</terminal>",
            temperature = 0.15f,
            topK = 12,
            maxOutputTokens = 160,
            allowThinking = false,
            onPartialText = onPartialText,
        )

        val rawResponse = outcome.text
        if (!rawResponse.isNullOrBlank()) {
            val structured = extractStructuredResult(rawResponse)
            return@withContext if (detectedActions.isNotEmpty()) {
                structured.copy(
                    usedAi = true,
                    hasQuestion = true,
                    quickActions = detectedActions,
                )
            } else {
                structured.copy(usedAi = true)
            }
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

    internal fun extractNumberedOptions(text: String): List<DigestQuickAction> {
        val recent = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(14)
        if (recent.size < 2) return emptyList()

        val lineRegex = Regex("^\\s*(?:[❯›>*•-]\\s*)?(\\d)[.)\\]]\\s+(.+)$")
        val bracketRegex = Regex("^\\s*\\[(\\d)]\\s+(.+)$")

        val candidates = mutableListOf<Pair<Int, String>>()
        for (line in recent) {
            val match = lineRegex.matchEntire(line) ?: bracketRegex.matchEntire(line)
            if (match == null) {
                // Keep only the bottom-most menu block if prose separated an older list.
                if (candidates.size >= 2) break
                candidates.clear()
                continue
            }
            val num = match.groupValues[1].toIntOrNull() ?: continue
            val desc = match.groupValues[2].trim()
            if (desc.isEmpty()) continue
            if (candidates.isEmpty()) {
                if (num == 1) candidates += num to desc
            } else if (num == candidates.last().first + 1 && candidates.size < 4) {
                candidates += num to desc
            } else if (num == 1) {
                candidates.clear()
                candidates += num to desc
            }
        }

        if (candidates.size < 2) return emptyList()
        return candidates.map { (num, desc) ->
            val shortDesc = if (desc.length <= 22) desc else desc.take(21).trimEnd() + "…"
            DigestQuickAction(label = "$num: $shortDesc", sendText = num.toString())
        }
    }

    internal fun fallbackAnalyze(output: String): AgentDigestResult {
        val allLines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) {
            return AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        val numberedActions = extractNumberedOptions(output)
        if (numberedActions.isNotEmpty()) {
            val promptHeader = allLines
                .dropLast(numberedActions.size)
                .lastOrNull { it.endsWith("?") || it.contains("allow", ignoreCase = true) || it.contains("choose", ignoreCase = true) || it.contains("select", ignoreCase = true) }
            val headline = promptHeader ?: "Choose an option:"
            return AgentDigestResult(
                spokenSummary = "The agent is waiting for your selection: $headline ${numberedActions.joinToString(", ") { it.label }}.",
                hasQuestion = true,
                question = headline,
                quickOptions = numberedActions.map { it.label },
                quickActions = numberedActions,
                isError = false,
                usedAi = false,
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
