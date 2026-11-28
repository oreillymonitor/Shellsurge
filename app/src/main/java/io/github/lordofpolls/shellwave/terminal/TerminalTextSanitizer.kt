package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator

/**
 * Extracts and sanitizes terminal output for AI digest and spoken read-back.
 */
object TerminalTextSanitizer {
    private val ANSI_PATTERN = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val SPINNER_CHARS = setOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏', '◐', '◓', '◑', '◒')
    private val BOX_BORDER_ONLY = Regex("^[┌┐└┘├┤┬┴┼╭╮╰╯─━═│┃▀▄█░▒▓\\-_*~·•\\s]+$")
    private val BOX_EDGE_STRIP = Regex("^[│┃║]\\s*(.*?)\\s*[│┃║]$")
    private val BOX_EDGE_PRESERVE_INDENT = Regex("^[│┃║] ?(.*?)\\s*[│┃║]$")
    private val TMUX_STATUS_LINE = Regex("^\\[[^\\]]+]\\s+\\d+:.*|\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s+\\d{1,2}-[A-Za-z]{3}-\\d{2,4})?\\s*$")
    private val PROGRESS_BAR_LINE = Regex(".*\\[[=>#\\-█▓▒░\\s]{5,}]\\s*\\d{1,3}%.*")
    private val TUI_CHROME_SUBSTRINGS = listOf(
        "? for shortcuts",
        "shift+tab",
        "ctrl+c to cancel",
        "ctrl+c to interrupt",
        "esc to interrupt",
        "esc to cancel",
        "auto-accept edits",
        "bypass permissions",
        "tokens used",
        "context left",
        "type a message",
        "type your message",
        "ask anything",
        "enter your message",
        "ask a follow-up",
        "type a follow-up",
        "send a message",
    )
    private val TUI_GHOST_PROMPT = Regex(
        "^[❯➜›>]\\s+(?:Try\\s+\"|Ask\\s+a\\s+follow-up|Type\\s+(?:a|your)\\s+message|Send\\s+a\\s+message).*",
        RegexOption.IGNORE_CASE,
    )
    private val TURN_START_PROMPT = Regex("^(?:[\\w.-]+@[\\w.-]+:.*[$#%]\\s+\\S.*|[❯➜›]\\s+(?!\\d+[.)\\]]\\s)\\S.*|\\$\\s+\\S.*)$")
    private val ANGLE_AGENT_PROMPT = Regex("^>\\s+(?!\\d+[.)\\]]\\s)\\S.*")
    private val GRADLE_ANGLE_LINE = Regex(
        "^>\\s+(?:Task\\b|Configure\\b|Run with\\b|Get more help\\b|What went wrong:|A problem occurred\\b|Execution failed\\b).*",
        RegexOption.IGNORE_CASE,
    )
    private val MARKDOWN_CALLOUT_LINE = Regex("^>\\s+\\[![A-Z]+].*")
    private val AGENT_ACTIVITY_LINE = Regex(
        "^[○●•*◦▸▹]\\s+(?:Thought|Thinking|Planned|Planning|Analyzed|Analyzing|Ran|Running|Explored|Exploring|Read|Reading|Edited|Editing|Wrote|Writing|Created|Creating|Searched|Searching|Listed|Listing|Viewed|Viewing|Checked|Checking|Called|Calling|Updated|Updating|Deleted|Deleting|Verified|Verifying|Fetched|Fetching|Built|Building|Tested|Testing)\\b.*",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PROMPT_LINE = Regex("^(?:[\\w.-]+@[\\w.-]+:.*[$#%]\\s*|[$#>❯➜›%]\\s*)$")
    private val DIFF_GIT_HEADER = Regex("^diff --git a/(.+?) b/(.+)$")

    /**
     * Extracts the tail of the buffer (up to [maxLines] lines), stripping ANSI escapes,
     * deduplicating spinner progress frames, and joining wrapped lines cleanly.
     */
    fun extractCleanTail(emulator: TerminalEmulator, maxLines: Int = 120): String {
        val rawText = readRawBuffer(emulator, maxRows = maxLines)
        return sanitize(rawText, maxLines)
    }

    /**
     * Extracts a high-signal snapshot of the latest command/agent turn for AI or rule-based
     * summarization, stripping TUI chrome, progress bars, tmux status bars, collapsing long
     * diffs, and slicing to the active turn when detectable.
     */
    fun extractTurnForDigest(emulator: TerminalEmulator, maxLines: Int = 60): String {
        val rawText = readRawBuffer(emulator, maxRows = 180)
        return cleanForDigest(rawText, maxLines)
    }

    /**
     * Extracts terminal output formatted for a full-screen selectable reader view, stripping TUI
     * chrome while keeping unified diffs and fenced code block indentation intact.
     */
    fun extractReadableOutput(
        emulator: TerminalEmulator,
        maxRows: Int? = null,
        latestTurnOnly: Boolean = true,
    ): String {
        val resolvedMaxRows = maxRows ?: if (latestTurnOnly) 400 else 2000
        val rawText = readRawBuffer(emulator, maxRows = resolvedMaxRows, joinFullLines = false)
        return cleanForReadable(rawText, latestTurnOnly = latestTurnOnly)
    }

    private fun readRawBuffer(
        emulator: TerminalEmulator,
        maxRows: Int,
        joinFullLines: Boolean = true,
    ): String {
        val screen = emulator.screen ?: return ""
        val activeTranscript = screen.activeTranscriptRows
        val startRow = (-activeTranscript).coerceAtLeast(-maxRows)
        val endRow = emulator.mRows - 1

        return try {
            screen.getSelectedText(0, startRow, emulator.mColumns, endRow, true, joinFullLines)
        } catch (_: Throwable) {
            screen.transcriptText ?: ""
        }
    }

    /**
     * Strips ANSI escapes, box-drawing borders, TUI footers/input placeholders, progress bars,
     * tmux status bars, and verbose unified diffs without slicing away older lines.
     */
    internal fun stripTuiChrome(raw: String): List<String> {
        val noAnsi = ANSI_PATTERN.replace(raw, "")
        val rawLines = noAnsi.lines()
        val normalized = mutableListOf<String>()

        var i = 0
        while (i < rawLines.size) {
            val rawLine = rawLines[i].trimEnd()
            val trimmed = rawLine.trim()

            // Collapse unified diff sections into one high-signal summary line.
            val diffMatch = DIFF_GIT_HEADER.matchEntire(trimmed)
            if (diffMatch != null) {
                val path = diffMatch.groupValues[2]
                var added = 0
                var removed = 0
                i++
                while (i < rawLines.size) {
                    val dLine = rawLines[i]
                    val dTrim = dLine.trim()
                    if (DIFF_GIT_HEADER.matches(dTrim) || TURN_START_PROMPT.matches(dTrim)) break
                    val inHunk = dLine.startsWith("diff ") ||
                        dLine.startsWith("index ") ||
                        dLine.startsWith("--- ") ||
                        dLine.startsWith("+++ ") ||
                        dLine.startsWith("@@") ||
                        dLine.startsWith("+") ||
                        dLine.startsWith("-") ||
                        dLine.startsWith(" ") ||
                        dLine.startsWith("\\ No newline") ||
                        dTrim.isEmpty()
                    if (!inHunk) break
                    if (dLine.startsWith("+") && !dLine.startsWith("+++")) added++
                    else if (dLine.startsWith("-") && !dLine.startsWith("---")) removed++
                    i++
                }
                normalized.add("Diff $path (+$added/-$removed)")
                continue
            }

            if (trimmed.isEmpty()) {
                if (normalized.isNotEmpty() && normalized.last().isNotEmpty()) {
                    normalized.add("")
                }
                i++
                continue
            }

            val unboxed = BOX_EDGE_STRIP.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
            if (unboxed.isEmpty() || BOX_BORDER_ONLY.matches(unboxed)) {
                i++
                continue
            }
            if (unboxed.length < 5 && unboxed.any { it in SPINNER_CHARS }) {
                i++
                continue
            }
            if (PROGRESS_BAR_LINE.matches(unboxed)) {
                i++
                continue
            }
            if (TMUX_STATUS_LINE.matches(unboxed)) {
                i++
                continue
            }
            if (TUI_GHOST_PROMPT.matches(unboxed)) {
                i++
                continue
            }
            if (TUI_CHROME_SUBSTRINGS.any { chrome -> unboxed.contains(chrome, ignoreCase = true) }) {
                i++
                continue
            }
            if (normalized.lastOrNull() != unboxed) {
                normalized.add(unboxed)
            }
            i++
        }

        return normalized
    }

    private fun isTransientChrome(unboxed: String): Boolean =
        (unboxed.length < 5 && unboxed.any { it in SPINNER_CHARS }) ||
            PROGRESS_BAR_LINE.matches(unboxed) ||
            TMUX_STATUS_LINE.matches(unboxed) ||
            TUI_GHOST_PROMPT.matches(unboxed) ||
            TUI_CHROME_SUBSTRINGS.any { chrome -> unboxed.contains(chrome, ignoreCase = true) }

    private fun isAngleAgentPromptStart(lines: List<String>, idx: Int): Boolean {
        val raw = lines[idx]
        if (!raw.startsWith("> ")) return false
        val candidate = raw.trim()
        if (!ANGLE_AGENT_PROMPT.matches(candidate)) return false
        if (GRADLE_ANGLE_LINE.matches(candidate) || MARKDOWN_CALLOUT_LINE.matches(candidate)) return false
        if (lines.getOrNull(idx + 1)?.trim()?.startsWith(">") == true) return false

        val nextRaw = lines.getOrNull(idx + 1)
        if (nextRaw != null &&
            nextRaw.trim().isNotEmpty() &&
            (nextRaw.startsWith("  ") || nextRaw.startsWith("\t")) &&
            !nextRaw.trim().startsWith(">")
        ) {
            return true
        }

        var j = idx + 1
        while (j < lines.size) {
            val probe = lines[j]
            val probeTrim = probe.trim()
            if (probeTrim.isEmpty()) break
            if ((probe.startsWith("  ") || probe.startsWith("\t")) && !probeTrim.startsWith(">")) {
                j++
            } else {
                break
            }
        }
        while (j < lines.size && lines[j].trim().isEmpty()) {
            j++
        }
        val lookahead = lines.getOrNull(j)?.trim() ?: return false
        return AGENT_ACTIVITY_LINE.matches(lookahead)
    }

    private fun sliceToLatestTurn(normalized: List<String>): List<String> {
        var lastCommandIdx = -1
        for (idx in normalized.indices.reversed()) {
            val candidate = normalized[idx].trim()
            if (candidate.isNotEmpty() &&
                (TURN_START_PROMPT.matches(candidate) || isAngleAgentPromptStart(normalized, idx))
            ) {
                val belowNonEmpty = normalized.drop(idx + 1).map { it.trim() }.filter { below ->
                    below.isNotEmpty() && !BARE_PROMPT_LINE.matches(below)
                }
                if (belowNonEmpty.isNotEmpty()) {
                    val isStrayTrailingTuiPrompt = (
                        candidate.startsWith("❯") ||
                            candidate.startsWith("➜") ||
                            candidate.startsWith("›") ||
                            candidate.startsWith(">")
                        ) &&
                        belowNonEmpty.size == 1 &&
                        normalized.take(idx).count { it.trim().isNotEmpty() && !BARE_PROMPT_LINE.matches(it.trim()) } >= 4 &&
                        belowNonEmpty[0].length < 60 &&
                        !belowNonEmpty[0].endsWith(".") &&
                        !belowNonEmpty[0].endsWith("?") &&
                        !belowNonEmpty[0].endsWith("!")
                    if (!isStrayTrailingTuiPrompt) {
                        lastCommandIdx = idx
                        break
                    }
                }
            }
        }
        return if (lastCommandIdx >= 0) {
            normalized.subList(lastCommandIdx, normalized.size)
        } else {
            normalized
        }
    }

    fun cleanForReadable(raw: String, latestTurnOnly: Boolean = true): String {
        val noAnsi = ANSI_PATTERN.replace(raw, "")
        val normalized = mutableListOf<String>()
        var inCodeFence = false

        fun appendBlankSeparator() {
            if (normalized.isNotEmpty() && normalized.last().isNotEmpty()) {
                normalized.add("")
            }
        }

        for (rawLine in noAnsi.lines()) {
            val trimmedEnd = rawLine.trimEnd()
            val trimmed = trimmedEnd.trim()

            if (inCodeFence) {
                normalized.add(trimmedEnd)
                if (trimmed.startsWith("```")) {
                    inCodeFence = false
                }
                continue
            }

            if (trimmed.isEmpty()) {
                appendBlankSeparator()
                continue
            }

            if (BOX_BORDER_ONLY.matches(trimmed)) {
                appendBlankSeparator()
                continue
            }

            val boxMatch = BOX_EDGE_PRESERVE_INDENT.matchEntire(trimmed)
            val contentLine = boxMatch?.groupValues?.get(1)?.trimEnd() ?: trimmedEnd
            val contentTrim = contentLine.trim()
            if (contentTrim.isEmpty() || BOX_BORDER_ONLY.matches(contentTrim)) {
                appendBlankSeparator()
                continue
            }
            if (contentTrim.startsWith("```")) {
                inCodeFence = true
                normalized.add(contentTrim)
                continue
            }
            if (isTransientChrome(contentTrim)) {
                continue
            }
            normalized.add(contentLine)
        }

        val sliced = if (latestTurnOnly) sliceToLatestTurn(normalized) else normalized
        return sliced
            .dropWhile { it.trim().isEmpty() }
            .dropLastWhile { it.trim().isEmpty() || BARE_PROMPT_LINE.matches(it.trim()) }
            .joinToString("\n")
    }

    fun cleanForDigest(raw: String, maxLines: Int = 60): String {
        val normalized = stripTuiChrome(raw)
        val turnLines = sliceToLatestTurn(normalized)

        return turnLines
            .dropWhile { it.isEmpty() }
            .takeLast(maxLines)
            .joinToString("\n")
            .trim()
    }

    private val GRADLE_BOILERPLATE_LINE = Regex(
        "^(?:\\*\\s*Try:|>\\s*Run with --(?:stacktrace|info|debug|scan)|>\\s*Get more help at|Deprecated Gradle features|You can use '--warning-mode all'|See https://docs\\.gradle\\.org|Configuration cache entry |\\d+ actionable tasks?:|\\[Incubating]\\s+Problems report).*"
    )
    private val GRADLE_NOOP_TASK_LINE = Regex(
        "^>\\s*Task\\s+:[^\\s]+(?:\\s+(?:UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED))?\\s*$"
    )
    private val FRAMEWORK_STACK_FRAME_LINE = Regex(
        "^\\s*(?:at\\s+(?:java|javax|sun|jdk|kotlin|kotlinx|org\\.gradle|org\\.junit|androidx|android|com\\.android)\\.|\\.\\.\\.\\s*\\d+\\s+more\\s*$).*"
    )
    private val TRANSFER_NOISE_LINE = Regex(
        "^(?:Downloading|Fetching|Resolving|Unpacking|Counting objects:|Compressing objects:|Writing objects:|remote:\\s*(?:Enumerating|Counting|Compressing|Total)).*"
    )
    private val TOOL_TRACE_LINE = Regex(
        "^(?:[●•*]\\s*)?(?:Read|Glob|Grep|Search|List|Viewed|Checked)\\b(?:\\(|:| ).{0,80}$"
    )
    private val SPECIFIC_ERROR_LINE = Regex(
        "(?i)(?:^e:\\s|error:|Exception:|AssertionError|FAILURE:|fatal:|Traceback|SyntaxError|TypeError)"
    )

    private fun isCruftLine(line: String): Boolean =
        GRADLE_BOILERPLATE_LINE.matches(line) ||
            GRADLE_NOOP_TASK_LINE.matches(line) ||
            FRAMEWORK_STACK_FRAME_LINE.matches(line) ||
            TRANSFER_NOISE_LINE.matches(line)

    /**
     * Packs a cleaned turn from the bottom up into a dense, low-cruft excerpt for on-device Nano
     * narration. Drops Gradle/build boilerplate, framework stack frames, transfer progress noise,
     * and repetitive tool-call trace blocks above the final answer, while anchoring the most
     * specific compiler/runtime error block if one appeared higher in the turn.
     */
    fun packTurnBottomUp(
        cleanedTurn: String,
        maxLines: Int = 20,
        maxChars: Int = 900,
    ): String {
        if (cleanedTurn.isBlank() || maxLines <= 0 || maxChars <= 0) return ""

        val nonPrompt = cleanedTurn.lines()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                t.isEmpty() || (!BARE_PROMPT_LINE.matches(t) && !isCruftLine(t))
            }
        val filtered = if (nonPrompt.any { it.isNotBlank() }) nonPrompt else cleanedTurn.lines()

        // Split into logical blocks on blank lines or new command/Diff boundaries.
        val blocks = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        fun flushBlock() {
            val cleaned = current.map { it.trimEnd() }.filter { it.isNotBlank() }
            if (cleaned.isNotEmpty()) blocks.add(cleaned)
            current.clear()
        }
        for (line in filtered) {
            val t = line.trim()
            if (t.isEmpty()) {
                flushBlock()
                continue
            }
            if ((TURN_START_PROMPT.matches(t) || DIFF_GIT_HEADER.matches(t) || t.startsWith("Diff ")) &&
                current.isNotEmpty()
            ) {
                flushBlock()
            }
            current.add(t)
        }
        flushBlock()
        if (blocks.isEmpty()) return ""

        fun isToolTraceBlock(block: List<String>): Boolean =
            block.all { TOOL_TRACE_LINE.matches(it) } &&
                block.none { SPECIFIC_ERROR_LINE.containsMatchIn(it) || it.endsWith("?") }

        val pickedIndices = mutableSetOf<Int>()
        var usedLines = 0
        var usedChars = 0

        for (idx in blocks.indices.reversed()) {
            val block = blocks[idx]
            if (pickedIndices.isNotEmpty() && isToolTraceBlock(block)) {
                continue
            }
            val blockText = block.joinToString("\n")
            val addedLines = block.size
            val addedChars = blockText.length + if (pickedIndices.isEmpty()) 0 else 1

            if (pickedIndices.isEmpty() && (addedLines > maxLines || addedChars > maxChars)) {
                // Bottom-most block alone exceeds budget: take its trailing lines within budget.
                val tailLines = ArrayDeque<String>()
                var chars = 0
                for (lIdx in block.indices.reversed()) {
                    val l = block[lIdx]
                    val nextChars = chars + l.length + if (tailLines.isEmpty()) 0 else 1
                    if (tailLines.size + 1 > maxLines || nextChars > maxChars) break
                    tailLines.addFirst(l)
                    chars = nextChars
                }
                if (tailLines.isNotEmpty()) {
                    blocks[idx] = tailLines.toList()
                    pickedIndices.add(idx)
                } else {
                    blocks[idx] = listOf(block.last().takeLast(maxChars))
                    pickedIndices.add(idx)
                }
                break
            }

            if (usedLines + addedLines > maxLines || usedChars + addedChars > maxChars) {
                continue
            }
            pickedIndices.add(idx)
            usedLines += addedLines
            usedChars += addedChars
        }

        val hasSpecificErrorAlready = pickedIndices.any { idx ->
            blocks[idx].any { SPECIFIC_ERROR_LINE.containsMatchIn(it) }
        }
        val anchorPrefix = if (!hasSpecificErrorAlready) {
            val errorIdx = (blocks.indices.reversed()).firstOrNull { idx ->
                idx !in pickedIndices && blocks[idx].any { SPECIFIC_ERROR_LINE.containsMatchIn(it) }
            }
            errorIdx?.let { idx ->
                val errorLines = blocks[idx].filter { SPECIFIC_ERROR_LINE.containsMatchIn(it) }
                    .ifEmpty { blocks[idx] }
                    .takeLast(4)
                val joined = errorLines.joinToString("\n")
                if (joined.length <= 280) joined else joined.take(279) + "…"
            }
        } else {
            null
        }

        val chronologicallyOrdered = pickedIndices.sorted().joinToString("\n") { idx ->
            blocks[idx].joinToString("\n")
        }
        return if (anchorPrefix != null) {
            "$anchorPrefix\n$chronologicallyOrdered"
        } else {
            chronologicallyOrdered
        }
    }

    fun sanitize(raw: String, maxLines: Int = 120): String {
        val noAnsi = ANSI_PATTERN.replace(raw, "")
        val lines = noAnsi.lines()
        val cleanedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty()) {
                if (cleanedLines.isNotEmpty() && cleanedLines.last().isNotEmpty()) {
                    cleanedLines.add("")
                }
                continue
            }
            // Skip transient spinner characters
            if (trimmed.length < 5 && trimmed.any { it in SPINNER_CHARS }) {
                continue
            }
            cleanedLines.add(trimmed)
        }

        val tail = cleanedLines.takeLast(maxLines)
        return tail.joinToString("\n").trim()
    }
}
