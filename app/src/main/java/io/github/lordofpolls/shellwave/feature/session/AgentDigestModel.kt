package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import io.github.lordofpolls.shellwave.terminal.TerminalTextSanitizer
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
    val headlineFact: String? = null,
    val touchedFiles: List<String> = emptyList(),
    val firstError: String? = null,
)

internal data class TerminalFacts(
    val headlineFact: String? = null,
    val firstError: String? = null,
    val touchedFiles: List<String> = emptyList(),
    val pendingQuestion: String? = null,
    val quickActions: List<DigestQuickAction> = emptyList(),
    val isYesNoPrompt: Boolean = false,
    val isError: Boolean = false,
)

/**
 * On-device terminal output digest engine combining deterministic TUI parsing & fact extraction
 * with compact 1-2 sentence Gemini Nano narration, plus an intelligent rule-based fallback.
 */
internal object AgentDigestModel {
    internal val DIGEST_SYSTEM_INSTRUCTION = """
        You summarize terminal output in 1 to 2 short spoken sentences for a developer.
        Report only concrete results, errors, or questions shown in the terminal text.
        Do not invent details. Use no greetings, bullet points, markdown, or labels.
    """.trimIndent()

    private val NANO_REFUSAL_PHRASES = listOf(
        "provided no ",
        "did not provide",
        "didn't provide",
        "nothing was provided",
        "was not provided",
        "not provided to",
        "no facts",
        "no turn",
        "no terminal output",
        "no output to summarize",
        "no text to summarize",
        "no content to summarize",
        "no draft",
        "no request",
        "no input provided",
        "missing input",
        "empty input",
        "cannot summarize",
        "can't summarize",
        "unable to summarize",
        "i cannot ",
        "i can't ",
        "as an ai",
        "please provide",
        "provide the terminal",
        "provide a draft",
        "provide a request",
    )

    internal fun isNanoRefusal(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 4) return true
        val lower = trimmed.lowercase()
        return NANO_REFUSAL_PHRASES.any { lower.contains(it) }
    }

    internal fun buildDigestPromptInput(facts: TerminalFacts, turnSnippet: String): String =
        buildString {
            appendLine("Summarize this terminal output in 1 to 2 short spoken sentences.")
            val details = buildList {
                facts.headlineFact?.let { add("Status: $it") }
                facts.firstError?.let { add("Error: $it") }
                if (facts.touchedFiles.isNotEmpty()) {
                    add("Files: ${facts.touchedFiles.joinToString(", ")}")
                }
                facts.pendingQuestion?.let { add("Question: $it") }
                if (facts.quickActions.isNotEmpty()) {
                    add("Options: ${facts.quickActions.joinToString("; ") { it.label }}")
                }
            }
            if (details.isNotEmpty()) {
                appendLine()
                appendLine("Key details:")
                details.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Terminal output:")
            append(turnSnippet)
        }

    private val YES_NO_REGEX = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
    private val YES_NO_CONFIRMATION_CUES = listOf(
        "confirm",
        "approve",
        "allow",
        "proceed",
        "continue",
        "would you like",
        "do you want",
        "should i",
        "shall i",
        "want me to",
        "apply",
    )
    private val PROMPT_LINE_REGEX = Regex("^[\\w.-]+@[\\w.-]+:.*[$#%]\\s*$|^[$#>❯➜%]\\s*$")
    private val DIFF_SUMMARY_REGEX = Regex("^Diff\\s+(.+?)\\s+\\(\\+\\d+/-\\d+\\)$")
    private val TOUCHED_VERB_REGEX = Regex(
        "(?i)\\b(?:edited|modified|updated|created|wrote|written|deleted|removed|edit|write|read)\\b\\s+[`\"']?((?:[\\w.-]+/)*[\\w.-]+\\.[a-zA-Z0-9]{1,8})[`\"']?"
    )
    private val COMPILER_FILE_REGEX = Regex(
        "(?:^|[\\s(\"'`])((?:[\\w./-]+/)*[\\w.-]+\\.(?:kt|kts|java|py|ts|tsx|js|jsx|rs|go|c|cc|cpp|h|hpp|rb|php|cs|swift|sh|yaml|yml|json|toml|xml|md|gradle|sql|css|scss|html))(?::\\d+)?"
    )
    private val OUTCOME_FACT_REGEX = Regex(
        "(?i)(BUILD SUCCESSFUL[^\\n]*|BUILD FAILED[^\\n]*|\\d+\\s+tests?\\s+(?:completed|passed|failed)[^\\n]*|All\\s+\\d+\\s+tests\\s+passed[^\\n]*|\\d+\\s+passed(?:,\\s*\\d+\\s+failed)?[^\\n]*|Tests run:[^\\n]*)"
    )

    internal fun extractTerminalFacts(cleanedTurn: String): TerminalFacts {
        val allLines = cleanedTurn.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) return TerminalFacts()

        val meaningful = allLines.filter { !PROMPT_LINE_REGEX.matches(it) }
        val pool = if (meaningful.isNotEmpty()) meaningful else allLines

        val numberedActions = extractNumberedOptions(cleanedTurn)
        val tail8 = pool.takeLast(8)
        val yesNoLine = tail8.lastOrNull { YES_NO_REGEX.containsMatchIn(it) }
        val isYesNoPrompt = yesNoLine != null

        val pendingQuestion: String? = when {
            numberedActions.isNotEmpty() -> {
                allLines.dropLast(numberedActions.size).lastOrNull { line ->
                    !PROMPT_LINE_REGEX.matches(line) && (
                        line.endsWith("?") ||
                            line.contains("allow", ignoreCase = true) ||
                            line.contains("approve", ignoreCase = true) ||
                            line.contains("choose", ignoreCase = true) ||
                            line.contains("select", ignoreCase = true) ||
                            line.startsWith("Bash(", ignoreCase = true) ||
                            line.startsWith("Edit(", ignoreCase = true) ||
                            line.startsWith("Write(", ignoreCase = true)
                        )
                } ?: "Choose an option:"
            }
            yesNoLine != null -> {
                YES_NO_REGEX.replace(yesNoLine, "").trim().removeSuffix("?") + "?"
            }
            else -> {
                pool.takeLast(2).lastOrNull { line ->
                    line.endsWith("?") ||
                        line.contains("confirm", ignoreCase = true) ||
                        line.contains("approve", ignoreCase = true) ||
                        line.contains("would you like", ignoreCase = true) ||
                        line.contains("do you want", ignoreCase = true)
                }
            }
        }

        // Prefer detailed compiler/runtime error lines over generic "BUILD FAILED in Xs" lines.
        val errorCandidates = pool.filter { line ->
            line.startsWith("e: ") ||
                line.contains("error:", ignoreCase = true) ||
                line.contains("Exception:", ignoreCase = true) ||
                line.contains("AssertionError", ignoreCase = true) ||
                line.contains("FAILURE:", ignoreCase = true) ||
                line.contains("fatal:", ignoreCase = true) ||
                line.contains("Traceback", ignoreCase = true) ||
                line.contains("SyntaxError", ignoreCase = true) ||
                line.contains("TypeError", ignoreCase = true) ||
                line.contains("BUILD FAILED", ignoreCase = true) ||
                line.contains("FAILURES!!!", ignoreCase = true)
        }
        val specificError = errorCandidates.lastOrNull { !it.startsWith("BUILD FAILED", ignoreCase = true) }
            ?: errorCandidates.lastOrNull()
        val firstError = specificError?.removePrefix("e: ")?.trim()?.take(140)

        var outcomeFact: String? = null
        for (line in pool.takeLast(12).reversed()) {
            val match = OUTCOME_FACT_REGEX.find(line)
            if (match != null) {
                outcomeFact = match.groupValues[1].trim()
                break
            }
        }
        if (outcomeFact == null) {
            outcomeFact = pool.takeLast(6).lastOrNull { line ->
                line.contains("committed", ignoreCase = true) ||
                    line.contains("pushed", ignoreCase = true) ||
                    line.contains("up to date", ignoreCase = true)
            }?.take(80)
        }

        val files = LinkedHashSet<String>()
        val editedFiles = LinkedHashSet<String>()
        for (line in allLines) {
            DIFF_SUMMARY_REGEX.matchEntire(line)?.let {
                val path = it.groupValues[1]
                files.add(path)
                editedFiles.add(path)
            }
            for (m in TOUCHED_VERB_REGEX.findAll(line)) {
                val path = m.groupValues[1]
                files.add(path)
                editedFiles.add(path)
            }
            for (m in COMPILER_FILE_REGEX.findAll(line)) {
                files.add(m.groupValues[1])
            }
        }
        val touchedFiles = files.take(4)

        val isError = firstError != null || outcomeFact?.contains("FAIL", ignoreCase = true) == true
        val isApprovalMenu = numberedActions.isNotEmpty() || (
            pendingQuestion != null && (
                pendingQuestion.contains("allow", ignoreCase = true) ||
                    pendingQuestion.contains("approve", ignoreCase = true) ||
                    pendingQuestion.contains("permission", ignoreCase = true)
                )
            )
        val headlineFact = when {
            isApprovalMenu -> "Approval needed"
            pendingQuestion != null -> "Waiting for input"
            outcomeFact != null -> outcomeFact.take(80)
            isError -> "Command failed"
            editedFiles.isNotEmpty() -> "Updated ${editedFiles.size} file${if (editedFiles.size == 1) "" else "s"}"
            else -> null
        }

        return TerminalFacts(
            headlineFact = headlineFact,
            firstError = firstError,
            touchedFiles = touchedFiles,
            pendingQuestion = pendingQuestion,
            quickActions = numberedActions,
            isYesNoPrompt = isYesNoPrompt,
            isError = isError,
        )
    }

    suspend fun analyzeTerminalTail(
        sanitizedOutput: String,
        context: Context? = null,
        onPartialText: ((String) -> Unit)? = null,
    ): AgentDigestResult = withContext(Dispatchers.IO) {
        val rawTrimmed = sanitizedOutput.trim()
        if (rawTrimmed.isBlank()) {
            return@withContext AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        val cleanedTurn = TerminalTextSanitizer.cleanForDigest(rawTrimmed, maxLines = 60)
            .ifBlank { rawTrimmed }
        val facts = extractTerminalFacts(cleanedTurn)

        val turnSnippet = TerminalTextSanitizer.packTurnBottomUp(cleanedTurn, maxLines = 20, maxChars = 900)
            .ifBlank { cleanedTurn.lines().takeLast(12).joinToString("\n") }
        val promptInput = buildDigestPromptInput(facts, turnSnippet)

        val outcome = NanoClientManager.generateText(
            context = context,
            systemInstruction = DIGEST_SYSTEM_INSTRUCTION,
            userText = promptInput,
            temperature = 0.1f,
            topK = 10,
            maxOutputTokens = 110,
            allowThinking = false,
            onPartialText = onPartialText?.let { callback ->
                { partial ->
                    if (!isNanoRefusal(partial)) callback(partial)
                }
            },
        )

        val rawResponse = outcome.text
        if (!rawResponse.isNullOrBlank() && !isNanoRefusal(rawResponse)) {
            val structured = extractStructuredResult(rawResponse)
            if (structured.spokenSummary.isNotBlank() && !isNanoRefusal(structured.spokenSummary)) {
                val isYesNoConfirmation = facts.pendingQuestion?.let { q ->
                    YES_NO_CONFIRMATION_CUES.any { cue -> q.contains(cue, ignoreCase = true) }
                } == true
                val hasQuestion = facts.quickActions.isNotEmpty() ||
                    facts.isYesNoPrompt ||
                    isYesNoConfirmation
                val quickOptions = when {
                    facts.quickActions.isNotEmpty() -> facts.quickActions.map { it.label }
                    hasQuestion -> listOf("Yes", "No")
                    else -> emptyList()
                }
                return@withContext structured.copy(
                    hasQuestion = hasQuestion,
                    question = if (hasQuestion) facts.pendingQuestion else null,
                    quickOptions = quickOptions,
                    quickActions = facts.quickActions,
                    isError = facts.isError || structured.isError,
                    usedAi = true,
                    headlineFact = facts.headlineFact,
                    touchedFiles = facts.touchedFiles,
                    firstError = facts.firstError,
                )
            }
        }

        // Fallback to intelligent rule-based extractor
        fallbackAnalyzeWithFacts(cleanedTurn, facts)
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

        val lineRegex = Regex("^\\s*([❯›>*•-])?\\s*(\\d)([.)\\]])\\s+(.+)$")
        val bracketRegex = Regex("^\\s*([❯›>*•-])?\\s*\\[(\\d)]\\s+(.+)$")
        val navFooterRegex = Regex("(?i)(esc|enter|↑|↓|arrow|cancel|select|choose|confirm)")
        val headerCueRegex = Regex(
            "(?i)(?:\\?\\s*$|\\b(?:allow|approve|permission|select|choose|pick|option|confirm|proceed|edit|delete|action)\\b|^(?:bash|edit|write)\\()"
        )

        data class MenuCandidate(
            val num: Int,
            val desc: String,
            val lineIdx: Int,
            val hasCursor: Boolean,
            val hasDotSyntax: Boolean,
        )

        val candidates = mutableListOf<MenuCandidate>()
        var latestBlock = emptyList<MenuCandidate>()
        for (idx in recent.indices) {
            val line = recent[idx]
            val lineMatch = lineRegex.matchEntire(line)
            val bracketMatch = if (lineMatch == null) bracketRegex.matchEntire(line) else null
            if (lineMatch == null && bracketMatch == null) {
                if (candidates.size >= 2) {
                    latestBlock = candidates.toList()
                }
                candidates.clear()
                continue
            }

            val cursorToken = (lineMatch?.groupValues?.get(1) ?: bracketMatch?.groupValues?.get(1)).orEmpty()
            val hasCursor = cursorToken in setOf("❯", "›", ">")
            val numStr = if (lineMatch != null) lineMatch.groupValues[2] else bracketMatch!!.groupValues[2]
            val hasDotSyntax = lineMatch != null && lineMatch.groupValues[3] == "."
            val desc = (if (lineMatch != null) lineMatch.groupValues[4] else bracketMatch!!.groupValues[3]).trim()

            val num = numStr.toIntOrNull() ?: continue
            if (desc.isEmpty()) continue
            if (candidates.isEmpty()) {
                if (num == 1) candidates += MenuCandidate(num, desc, idx, hasCursor, hasDotSyntax)
            } else if (num == candidates.last().num + 1 && candidates.size < 4) {
                candidates += MenuCandidate(num, desc, idx, hasCursor, hasDotSyntax)
            } else if (num == 1) {
                if (candidates.size >= 2) {
                    latestBlock = candidates.toList()
                }
                candidates.clear()
                candidates += MenuCandidate(num, desc, idx, hasCursor, hasDotSyntax)
            }
        }
        if (candidates.size >= 2) {
            latestBlock = candidates.toList()
        }

        if (latestBlock.size < 2) return emptyList()

        // Reject prose markdown lists whose items are long explanations or multi-sentence paragraphs.
        if (latestBlock.any { c ->
                c.desc.length > 80 || c.desc.contains(" — ") || c.desc.contains(". ")
            }
        ) {
            return emptyList()
        }

        // Reject if conversational prose trails below the menu (allow at most 1 short navigation hint).
        val trailingLines = recent.drop(latestBlock.last().lineIdx + 1)
        if (trailingLines.size > 1 ||
            trailingLines.any { line -> line.length > 50 || !navFooterRegex.containsMatchIn(line) }
        ) {
            return emptyList()
        }

        // Plain `1. / 2.` markdown lists without a selector cursor must sit beneath an interactive header.
        val anyCursor = latestBlock.any { it.hasCursor }
        val allPlainDot = latestBlock.all { it.hasDotSyntax }
        if (allPlainDot && !anyCursor) {
            val firstIdx = latestBlock.first().lineIdx
            val headerWindow = recent.subList((firstIdx - 3).coerceAtLeast(0), firstIdx)
            if (headerWindow.none { headerCueRegex.containsMatchIn(it) }) {
                return emptyList()
            }
        }

        return latestBlock.map { c ->
            val shortDesc = if (c.desc.length <= 22) c.desc else c.desc.take(21).trimEnd() + "…"
            DigestQuickAction(label = "${c.num}: $shortDesc", sendText = c.num.toString())
        }
    }

    internal fun fallbackAnalyze(output: String): AgentDigestResult =
        fallbackAnalyzeWithFacts(output, extractTerminalFacts(output))

    private fun fallbackAnalyzeWithFacts(output: String, facts: TerminalFacts): AgentDigestResult {
        val allLines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) {
            return AgentDigestResult(
                spokenSummary = "Terminal is idle with no recent output.",
                hasQuestion = false,
            )
        }

        val numberedActions = facts.quickActions.ifEmpty { extractNumberedOptions(output) }
        if (numberedActions.isNotEmpty()) {
            val headline = facts.pendingQuestion ?: "Choose an option:"
            return AgentDigestResult(
                spokenSummary = "Waiting for selection: $headline ${numberedActions.joinToString(", ") { it.label }}.",
                hasQuestion = true,
                question = headline,
                quickOptions = numberedActions.map { it.label },
                quickActions = numberedActions,
                isError = facts.isError,
                usedAi = false,
                headlineFact = facts.headlineFact ?: "Approval needed",
                touchedFiles = facts.touchedFiles,
                firstError = facts.firstError,
            )
        }

        val meaningfulLines = allLines.filter { line ->
            !PROMPT_LINE_REGEX.matches(line) && !line.endsWith("$") && !line.endsWith("#") && !line.endsWith(">")
        }

        val analysisPool = if (meaningfulLines.isNotEmpty()) meaningfulLines else allLines
        val lastFew = analysisPool.takeLast(12)

        // 1. Detect pending yes/no or approval prompts (not arbitrary open-ended prose questions).
        val questionLine = lastFew.lastOrNull { line ->
            YES_NO_REGEX.containsMatchIn(line) ||
                (line.endsWith("?") && YES_NO_CONFIRMATION_CUES.any { cue -> line.contains(cue, ignoreCase = true) }) ||
                line.contains("confirm", ignoreCase = true)
        }

        if (questionLine != null) {
            val cleanQuestion = YES_NO_REGEX.replace(questionLine, "").trim().removeSuffix("?") + "?"
            return AgentDigestResult(
                spokenSummary = "Waiting for your confirmation: $cleanQuestion",
                hasQuestion = true,
                question = cleanQuestion,
                quickOptions = listOf("Yes", "No"),
                isError = facts.isError,
                usedAi = false,
                headlineFact = facts.headlineFact ?: "Waiting for input",
                touchedFiles = facts.touchedFiles,
                firstError = facts.firstError,
            )
        }

        // 2. Detect error or failure
        if (facts.isError || facts.firstError != null) {
            val cleanError = facts.firstError ?: "Execution failed"
            return AgentDigestResult(
                spokenSummary = "Command failed: $cleanError",
                hasQuestion = false,
                isError = true,
                usedAi = false,
                headlineFact = facts.headlineFact ?: "Command failed",
                touchedFiles = facts.touchedFiles,
                firstError = cleanError,
            )
        }

        // 3. Detect build success or completed task
        val successLine = lastFew.lastOrNull { line ->
            line.contains("BUILD SUCCESSFUL", ignoreCase = true) ||
                line.contains("Tests run:", ignoreCase = true) ||
                line.contains("passed in", ignoreCase = true) ||
                line.contains("all tests passed", ignoreCase = true) ||
                line.contains("up to date", ignoreCase = true) ||
                line.contains("done", ignoreCase = true) ||
                line.contains("completed", ignoreCase = true) ||
                line.contains("committed", ignoreCase = true) ||
                line.contains("pushed", ignoreCase = true)
        }
        if (successLine != null) {
            return AgentDigestResult(
                spokenSummary = "Completed successfully: $successLine.",
                hasQuestion = false,
                isError = false,
                usedAi = false,
                headlineFact = facts.headlineFact,
                touchedFiles = facts.touchedFiles,
                firstError = null,
            )
        }

        // 4. Summarize the last meaningful command or agent outputs
        if (meaningfulLines.isNotEmpty()) {
            val summaryText = meaningfulLines.takeLast(2).joinToString(". ")
            return AgentDigestResult(
                spokenSummary = summaryText,
                hasQuestion = false,
                isError = false,
                usedAi = false,
                headlineFact = facts.headlineFact,
                touchedFiles = facts.touchedFiles,
                firstError = null,
            )
        }

        // True idle prompt
        return AgentDigestResult(
            spokenSummary = "Terminal is idle at the command prompt.",
            hasQuestion = false,
            isError = false,
            usedAi = false,
        )
    }
}
