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
    private val TMUX_STATUS_LINE = Regex("^\\[[^\\]]+]\\s+\\d+:.*|\\b\\d{1,2}:\\d{2}\\s*$")
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
        "send a message",
    )
    private val TURN_START_PROMPT = Regex("^(?:[\\w.-]+@[\\w.-]+:.*[$#%]\\s+\\S.*|[❯➜]\\s+(?!\\d+[.)\\]]\\s)\\S.*|\\$\\s+\\S.*)$")
    private val BARE_PROMPT_LINE = Regex("^(?:[\\w.-]+@[\\w.-]+:.*[$#%]\\s*|[$#>❯➜%]\\s*)$")
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

    private fun readRawBuffer(emulator: TerminalEmulator, maxRows: Int): String {
        val screen = emulator.screen ?: return ""
        val activeTranscript = screen.activeTranscriptRows
        val startRow = (-activeTranscript).coerceAtLeast(-maxRows)
        val endRow = emulator.mRows - 1

        return try {
            screen.getSelectedText(0, startRow, emulator.mColumns, endRow, true, true)
        } catch (_: Throwable) {
            screen.transcriptText ?: ""
        }
    }

    fun cleanForDigest(raw: String, maxLines: Int = 60): String {
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
            if (TUI_CHROME_SUBSTRINGS.any { chrome -> unboxed.contains(chrome, ignoreCase = true) }) {
                i++
                continue
            }
            if (normalized.lastOrNull() != unboxed) {
                normalized.add(unboxed)
            }
            i++
        }

        // Slice to the latest command turn if an earlier command prompt sits above subsequent output.
        var lastCommandIdx = -1
        for (idx in normalized.indices.reversed()) {
            val candidate = normalized[idx]
            if (candidate.isNotEmpty() && TURN_START_PROMPT.matches(candidate)) {
                val hasContentBelow = normalized.drop(idx + 1).any { below ->
                    below.isNotEmpty() && !BARE_PROMPT_LINE.matches(below)
                }
                if (hasContentBelow) {
                    lastCommandIdx = idx
                    break
                }
            }
        }

        val turnLines = if (lastCommandIdx >= 0) {
            normalized.subList(lastCommandIdx, normalized.size)
        } else {
            normalized
        }

        return turnLines
            .dropWhile { it.isEmpty() }
            .takeLast(maxLines)
            .joinToString("\n")
            .trim()
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
