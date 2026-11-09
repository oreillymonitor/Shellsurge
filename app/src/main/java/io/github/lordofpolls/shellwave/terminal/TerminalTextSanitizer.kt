package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator

/**
 * Extracts and sanitizes terminal output for AI digest and spoken read-back.
 */
object TerminalTextSanitizer {
    private val ANSI_PATTERN = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val SPINNER_CHARS = setOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏', '◐', '◓', '◑', '◒')

    /**
     * Extracts the tail of the buffer (up to [maxLines] lines), stripping ANSI escapes,
     * deduplicating spinner progress frames, and joining wrapped lines cleanly.
     */
    fun extractCleanTail(emulator: TerminalEmulator, maxLines: Int = 120): String {
        val screen = emulator.screen ?: return ""
        val activeTranscript = screen.activeTranscriptRows
        val startRow = (-activeTranscript).coerceAtLeast(-maxLines)
        val endRow = screen.screenRows - 1

        val rawText = try {
            screen.getSelectedText(0, startRow, screen.columns, endRow, true, true)
        } catch (_: Throwable) {
            screen.transcriptText ?: ""
        }

        return sanitize(rawText, maxLines)
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
