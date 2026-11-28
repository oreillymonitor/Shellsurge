package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import io.github.lordofpolls.shellwave.ssh.NotificationQuickAction
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
    val editedFiles: List<String> = emptyList(),
    val firstError: String? = null,
)

internal data class TerminalFacts(
    val headlineFact: String? = null,
    val firstError: String? = null,
    val touchedFiles: List<String> = emptyList(),
    val editedFiles: List<String> = emptyList(),
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
        "ready to",
        "want to",
        "go ahead",
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
    private val TOOL_ACTIVITY_SUMMARY_REGEX = Regex(
        "^(?:[○●•*◦▸▹]\\s+(?:Thought|Thinking|Planned|Planning|Analyzed|Analyzing|Ran|Running|Explored|Exploring|Read|Reading|Edited|Editing|Wrote|Writing|Created|Creating|Searched|Searching|Listed|Listing|Viewed|Viewing|Checked|Checking|Called|Calling|Updated|Updating|Deleted|Deleting|Verified|Verifying|Fetched|Fetching|Built|Building|Tested|Testing)\\b.*|(?:Read|Glob|Grep|Search|Edit|Write|Bash|Task|WebFetch|WebSearch)\\([^)]*\\)\\s*$|\\(ctrl\\+o to expand\\))$",
        RegexOption.IGNORE_CASE,
    )
    private val STRUCTURAL_LINE_START_REGEX = Regex(
        "^(?:[-*•●◦▸▹]|\\d+[.)\\]]\\s+|#+\\s+|Diff\\s+|e:\\s+|(?:error|failure|fatal|exception|traceback|build failed|build successful)\\b[:\\s]).*",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanDigestLine(line: String): String =
        line.replace(Regex("^#+\\s+"), "")
            .replace("**", "")
            .replace("`", "")
            .trim()

    internal fun reflowWrappedLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val prev = result.lastOrNull()
            if (prev == null) {
                result += line
                continue
            }
            val prevEndsSentence = prev.endsWith('.') ||
                prev.endsWith('!') ||
                prev.endsWith('?') ||
                prev.endsWith(':') ||
                prev.endsWith(';')
            val prevIsStructural = STRUCTURAL_LINE_START_REGEX.matches(prev) ||
                TOOL_ACTIVITY_SUMMARY_REGEX.containsMatchIn(prev) ||
                PROMPT_LINE_REGEX.matches(prev)
            val nextIsStructural = STRUCTURAL_LINE_START_REGEX.matches(line) ||
                TOOL_ACTIVITY_SUMMARY_REGEX.containsMatchIn(line) ||
                PROMPT_LINE_REGEX.matches(line)

            if (!prevEndsSentence && !prevIsStructural && !nextIsStructural) {
                result[result.lastIndex] = "$prev $line"
            } else {
                result += line
            }
        }
        return result
    }

    private val TWO_CHOICE_QUESTION_REGEX = Regex(
        "(?i)(?:would you like(?: me)? to|do you want(?: me)? to|should i|want me to)\\s+(.+?)(?:,\\s*or\\s+|\\s+or\\s+)(?:(?:should i|would you like(?: me)? to|do you want(?: me)? to)\\s+)?(.+?)\\??$",
    )
    private val VIA_CLAUSE_REGEX = Regex("\\s+(?:via|using)\\s+.*$", RegexOption.IGNORE_CASE)

    private fun truncateQuickActionLabel(label: String, maxChars: Int = 20): String {
        val trimmed = label.trim()
        return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars - 1).trimEnd() + "…"
    }

    private fun extractChoiceQuickActions(question: String?): List<NotificationQuickAction> {
        val q = question?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val match = TWO_CHOICE_QUESTION_REGEX.find(q) ?: return emptyList()
        val choices = listOf(match.groupValues[1], match.groupValues[2]).mapNotNull { raw ->
            val cleaned = cleanDigestLine(raw)
            val withoutVia = VIA_CLAUSE_REGEX.replace(cleaned, "").trim().trimEnd(',', '.', '?', ';')
            val finalSend = withoutVia.ifBlank { cleaned.trimEnd(',', '.', '?', ';') }.trim()
            if (finalSend.length < 2) {
                null
            } else {
                val label = truncateQuickActionLabel(
                    finalSend.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() },
                )
                NotificationQuickAction(label = label, sendText = finalSend.take(64))
            }
        }
        return if (choices.size == 2 && !choices[0].sendText.equals(choices[1].sendText, ignoreCase = true)) {
            choices
        } else {
            emptyList()
        }
    }

    internal fun resolveNotificationQuickActions(
        watcherActions: List<NotificationQuickAction>,
        digest: AgentDigestResult,
        isResponseNeeded: Boolean,
    ): List<NotificationQuickAction> {
        if (watcherActions.isNotEmpty()) return watcherActions.take(2)
        if (digest.quickActions.isNotEmpty()) {
            return digest.quickActions.take(2).map { action ->
                NotificationQuickAction(
                    label = truncateQuickActionLabel(action.label),
                    sendText = action.sendText,
                )
            }
        }
        if (isResponseNeeded || digest.hasQuestion) {
            val choiceActions = extractChoiceQuickActions(digest.question)
            if (choiceActions.isNotEmpty()) return choiceActions
            return if (isResponseNeeded) {
                listOf(
                    NotificationQuickAction("Yes", "y"),
                    NotificationQuickAction("No", "n"),
                )
            } else {
                listOf(
                    NotificationQuickAction("Yes", "yes"),
                    NotificationQuickAction("No", "no"),
                )
            }
        }
        return emptyList()
    }

    internal fun formatNotificationTitle(
        isResponseNeeded: Boolean,
        digest: AgentDigestResult,
    ): String {
        val headline = digest.headlineFact?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            isResponseNeeded -> headline?.takeIf {
                it in setOf("Approval needed", "Action needed", "Input needed")
            } ?: "Input needed"
            digest.isError -> (headline ?: "Command failed").take(48)
            digest.hasQuestion -> (headline ?: "Input needed").take(48)
            else -> (headline ?: "Ready for next prompt").take(48)
        }
    }

    internal fun formatNotificationBody(
        digest: AgentDigestResult,
        fallbackBody: String,
        maxChars: Int = 360,
    ): String {
        val base = digest.spokenSummary.trim().ifBlank { fallbackBody.trim() }.ifBlank { "Ready for next prompt." }
        val extras = mutableListOf<String>()
        val err = digest.firstError?.trim()?.takeIf { it.isNotEmpty() }
        val filesForFooter = when {
            digest.editedFiles.isNotEmpty() -> digest.editedFiles
            digest.headlineFact?.startsWith("Updated ") == true -> digest.touchedFiles
            else -> emptyList()
        }
        if (digest.isError && err != null && !base.contains(err.take(28), ignoreCase = true)) {
            extras += "Error: $err"
        } else if (filesForFooter.isNotEmpty()) {
            val shortFiles = filesForFooter.take(3).map { path ->
                path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            }.distinct()
            val unseenFiles = shortFiles.filterNot { name -> base.contains(name, ignoreCase = true) }
            if (unseenFiles.isNotEmpty()) {
                extras += "Files: ${unseenFiles.joinToString(", ")}"
            }
        }
        val combined = if (extras.isEmpty()) base else "$base\n${extras.joinToString("\n")}"
        return if (combined.length <= maxChars) {
            combined
        } else {
            combined.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"
        }
    }

    internal fun extractTerminalFacts(cleanedTurn: String): TerminalFacts {
        val allLines = cleanedTurn.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) return TerminalFacts()

        val meaningful = allLines.filter { !PROMPT_LINE_REGEX.matches(it) }
        val pool = if (meaningful.isNotEmpty()) meaningful else allLines
        val reflowedPool = reflowWrappedLines(pool)
        val nonToolReflowed = reflowedPool
            .filterNot { TOOL_ACTIVITY_SUMMARY_REGEX.containsMatchIn(it) || it.startsWith("> ") || it.startsWith("❯ ") }
            .ifEmpty { reflowedPool }

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
                nonToolReflowed.takeLast(2).lastOrNull { line ->
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
        for (line in reflowedPool.takeLast(8).reversed()) {
            val match = OUTCOME_FACT_REGEX.find(line)
            if (match != null) {
                outcomeFact = match.groupValues[1].trim()
                break
            }
        }
        if (outcomeFact == null) {
            val tailText = nonToolReflowed.takeLast(4).joinToString(" ")
            outcomeFact = when {
                tailText.contains("pushed", ignoreCase = true) -> "Changes pushed"
                tailText.contains("committed", ignoreCase = true) -> "Changes committed"
                tailText.contains("up to date", ignoreCase = true) -> "Already up to date"
                else -> null
            }
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
            isYesNoPrompt -> "Action needed"
            pendingQuestion != null -> "Input needed"
            outcomeFact != null -> outcomeFact.take(64)
            isError -> "Command failed"
            editedFiles.isNotEmpty() -> "Updated ${editedFiles.size} file${if (editedFiles.size == 1) "" else "s"}"
            else -> null
        }

        return TerminalFacts(
            headlineFact = headlineFact,
            firstError = firstError,
            touchedFiles = touchedFiles,
            editedFiles = editedFiles.take(4),
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
                    editedFiles = facts.editedFiles,
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
                editedFiles = facts.editedFiles,
                firstError = facts.firstError,
            )
        }

        val meaningfulLines = allLines.filter { line ->
            !PROMPT_LINE_REGEX.matches(line) && !line.endsWith("$") && !line.endsWith("#") && !line.endsWith(">")
        }

        val analysisPool = if (meaningfulLines.isNotEmpty()) meaningfulLines else allLines
        val reflowedPool = reflowWrappedLines(analysisPool)
        val nonToolParagraphs = reflowedPool
            .filterNot { TOOL_ACTIVITY_SUMMARY_REGEX.containsMatchIn(it) || it.startsWith("> ") || it.startsWith("❯ ") }
            .ifEmpty { reflowedPool }

        // 1. Detect pending yes/no or approval prompts (only at the tail of the latest turn).
        val questionCandidate = nonToolParagraphs.takeLast(2).lastOrNull { line ->
            YES_NO_REGEX.containsMatchIn(line) ||
                (line.endsWith("?") && YES_NO_CONFIRMATION_CUES.any { cue -> line.contains(cue, ignoreCase = true) }) ||
                line.contains("confirm", ignoreCase = true)
        }

        if (questionCandidate != null) {
            val cleanQuestion = cleanDigestLine(
                YES_NO_REGEX.replace(questionCandidate, "").trim().removeSuffix("?") + "?",
            )
            val questionIdx = nonToolParagraphs.lastIndexOf(questionCandidate)
            val earlier = if (questionIdx > 0) nonToolParagraphs.subList(0, questionIdx) else emptyList()
            val lead = earlier.firstOrNull { line ->
                val cleaned = cleanDigestLine(line)
                !line.trimStart().startsWith("#") &&
                    !line.trimStart().startsWith("-") &&
                    !line.trimStart().startsWith("•") &&
                    !line.trimStart().startsWith("```") &&
                    cleaned.length in 12..140
            }?.let { cleanDigestLine(it) } ?: earlier.firstOrNull { line ->
                OUTCOME_FACT_REGEX.containsMatchIn(line)
            }?.let { cleanDigestLine(it) }
            val spoken = if (lead != null && !cleanQuestion.contains(lead.take(24), ignoreCase = true)) {
                "$lead\n$cleanQuestion"
            } else {
                cleanQuestion
            }
            return AgentDigestResult(
                spokenSummary = spoken,
                hasQuestion = true,
                question = cleanQuestion,
                quickOptions = listOf("Yes", "No"),
                isError = facts.isError,
                usedAi = false,
                headlineFact = facts.headlineFact ?: if (facts.isYesNoPrompt) "Action needed" else "Input needed",
                touchedFiles = facts.touchedFiles,
                editedFiles = facts.editedFiles,
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
                editedFiles = facts.editedFiles,
                firstError = cleanError,
            )
        }

        // 3. Detect build success or completed task
        val successLine = nonToolParagraphs.takeLast(4).lastOrNull { line ->
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
            val cleanSuccess = cleanDigestLine(successLine).trim().trimEnd('.')
            val alreadyStatesOutcome =
                Regex("(?i)\\b(?:completed|committed|pushed|success)\\b").containsMatchIn(cleanSuccess) &&
                    !cleanSuccess.contains("BUILD SUCCESSFUL", ignoreCase = true)
            val summary = if (alreadyStatesOutcome) {
                cleanSuccess.replaceFirstChar { it.uppercase() } + "."
            } else {
                "Completed successfully: $cleanSuccess."
            }
            return AgentDigestResult(
                spokenSummary = summary,
                hasQuestion = false,
                isError = false,
                usedAi = false,
                headlineFact = facts.headlineFact,
                touchedFiles = facts.touchedFiles,
                editedFiles = facts.editedFiles,
                firstError = null,
            )
        }

        // 4. Summarize the last meaningful command or agent outputs
        if (meaningfulLines.isNotEmpty()) {
            val cleanedNonTool = nonToolParagraphs.map { cleanDigestLine(it) }.filter { it.isNotEmpty() }
            val nonHeading = cleanedNonTool.filterNot { it.endsWith(":") }
                .ifEmpty { cleanedNonTool }
            val picked = if (nonHeading.size >= 2 && nonHeading.first().length in 16..140) {
                listOf(nonHeading.first(), nonHeading.last()).distinct()
            } else {
                nonHeading.takeLast(2)
            }
            val joined = picked.joinToString(" ").trim()
            val cleanSummary = if (joined.length <= 240) {
                joined
            } else {
                val cutoff = joined.lastIndexOf(' ', startIndex = 239).takeIf { it > 120 } ?: 239
                joined.take(cutoff).trimEnd() + "…"
            }
            return AgentDigestResult(
                spokenSummary = cleanSummary,
                hasQuestion = false,
                isError = false,
                usedAi = false,
                headlineFact = facts.headlineFact,
                touchedFiles = facts.touchedFiles,
                editedFiles = facts.editedFiles,
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
