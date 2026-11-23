package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_QUICK_ECHO_MS = 1_500L
private const val MIN_QUICK_ECHO_BYTES = 24

private val PROMPT_LINE_REGEX =
    Regex("^[\\w.-]+@[\\w.-]+:.*[$#%]\\s*$|^[$#>❯➜%]\\s*$|^[>❯➜]\\s+.*$")
private val DIVIDER_LINE_REGEX = Regex("^[─━═\\-_*~·•]+$")
private val QUESTION_REGEX = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")

private val IN_PROGRESS_MARKERS = listOf(
    "thinking...",
    "thinking…",
    "working...",
    "working…",
    "generating...",
    "generating…",
    "executing...",
    "compiling...",
    "building...",
    "downloading...",
    "esc to interrupt",
    "esc to cancel",
    "ctrl+c to cancel",
    "ctrl+c to interrupt",
)

private val RESPONSE_NEEDED_MARKERS = listOf(
    "do you want to",
    "would you like to",
    "allow ",
    "approve",
    "confirm",
    "permission",
    "proceed?",
    "continue?",
    "select an option",
    "choose an option",
    "press enter",
    "waiting for your",
    "apply changes",
    "apply these changes",
    "accept changes",
    "password:",
    "passphrase",
)

private val COMPLETED_MARKERS = listOf(
    "build successful",
    "build failed",
    "failures!!!",
    "failure:",
    "all tests passed",
    "tests passed",
    "completed",
    "finished",
    "done",
    "committed",
    "pushed",
    "up to date",
    "error:",
    "fatal:",
    "exception:",
)

internal enum class OutputPromptKind(val titleSuffix: String) {
    COMPLETED("Completed"),
    RESPONSE_NEEDED("Response needed"),
}

internal fun formatPromptTitle(label: String, kind: OutputPromptKind): String =
    "$label · ${kind.titleSuffix}"

internal fun containsCommandSubmit(
    data: ByteArray,
    offset: Int = 0,
    count: Int = data.size,
): Boolean {
    val start = offset.coerceIn(0, data.size)
    val end = (start + count).coerceIn(start, data.size)
    for (i in start until end) {
        val b = data[i]
        if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) return true
    }
    return false
}

internal fun extractOutputSnippet(sanitizedTail: String, maxChars: Int = 220): String {
    val lines = sanitizedTail.lines()
        .map { it.trim() }
        .filter { line ->
            line.isNotEmpty() &&
                !PROMPT_LINE_REGEX.matches(line) &&
                !DIVIDER_LINE_REGEX.matches(line)
        }
    if (lines.isEmpty()) return "Command finished."
    val joined = lines.takeLast(3).joinToString("\n")
    return if (joined.length <= maxChars) {
        joined
    } else {
        joined.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"
    }
}

private fun isResponseNeededText(text: String): Boolean {
    val lines = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (lines.isEmpty()) return false
    val window = lines.takeLast(6)
    if (window.any { line ->
            QUESTION_REGEX.containsMatchIn(line) ||
                RESPONSE_NEEDED_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
    ) {
        return true
    }
    val nonPromptTail = window.filter { !PROMPT_LINE_REGEX.matches(it) }.takeLast(2)
    return nonPromptTail.any { it.endsWith("?") }
}

/**
 * Classifies terminal output into strictly two actionable notification types:
 * [OutputPromptKind.RESPONSE_NEEDED] or [OutputPromptKind.COMPLETED], or `null` when output is
 * still streaming/in-progress and should not trigger a notification.
 */
internal fun classifyPromptKind(
    sanitizedTail: String,
    explicitSignal: TerminalSignal? = null,
): OutputPromptKind? {
    when (explicitSignal) {
        is TerminalSignal.CommandFinished -> {
            return if (isResponseNeededText(sanitizedTail)) {
                OutputPromptKind.RESPONSE_NEEDED
            } else {
                OutputPromptKind.COMPLETED
            }
        }

        is TerminalSignal.OscNotification -> {
            return if (isResponseNeededText(explicitSignal.body) || isResponseNeededText(sanitizedTail)) {
                OutputPromptKind.RESPONSE_NEEDED
            } else {
                OutputPromptKind.COMPLETED
            }
        }

        is TerminalSignal.CommandStarted -> return null
        null -> Unit
    }

    val lines = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (lines.isEmpty()) return null

    // 1. Check if a response / confirmation is needed first.
    if (isResponseNeededText(sanitizedTail)) {
        return OutputPromptKind.RESPONSE_NEEDED
    }

    // 2. If bottom lines show an active in-progress/thinking state, wait instead of notifying early.
    val bottomWindow = lines.takeLast(4)
    val activelyRunning = bottomWindow.any { line ->
        IN_PROGRESS_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }
    if (activelyRunning) return null

    // 3. Check if the command/turn completed (returned to a prompt or printed a completion summary).
    val meaningfulLines = lines.filter { !PROMPT_LINE_REGEX.matches(it) }
    if (meaningfulLines.isEmpty()) return null

    val bottomLine = lines.last()
    val returnedToPrompt = PROMPT_LINE_REGEX.matches(bottomLine)
    val hasCompletionMarker = meaningfulLines.takeLast(6).any { line ->
        COMPLETED_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }

    return if (returnedToPrompt || hasCompletionMarker) {
        OutputPromptKind.COMPLETED
    } else {
        null
    }
}

/**
 * Watches one interactive SSH shell's input/output stream to notify at most once per turn when a
 * command reaches [OutputPromptKind.COMPLETED] or [OutputPromptKind.RESPONSE_NEEDED].
 */
internal class TerminalOutputWatcher(
    private val scope: CoroutineScope,
    private val label: String,
    private val isEnabled: () -> Boolean,
    private val idleDelayMs: () -> Long,
    private val readTail: suspend () -> String,
    private val onAlert: (kind: OutputPromptKind, title: String, body: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val scanner = TerminalNotificationScanner()

    private var turnArmed = false
    private var notifiedResponseNeededInTurn = false
    private var lastNotifiedSignature: String? = null
    private var commandSubmittedAtMs: Long? = null
    private var lastOutputAtMs: Long = 0L
    private var outputBytesSinceSubmit = 0
    private var settleJob: Job? = null

    fun onUserWrite(data: ByteArray) {
        synchronized(lock) {
            val isSubmit = containsCommandSubmit(data)
            val isInteractiveReply = data.isNotEmpty() && notifiedResponseNeededInTurn
            if (!isSubmit && !isInteractiveReply) return
            turnArmed = true
            notifiedResponseNeededInTurn = false
            commandSubmittedAtMs = nowMs()
            lastOutputAtMs = 0L
            outputBytesSinceSubmit = 0
            settleJob?.cancel()
            settleJob = null
        }
    }

    fun onRawOutput(data: ByteArray, length: Int = data.size) {
        val explicitSignals = mutableListOf<TerminalSignal>()

        synchronized(lock) {
            val scanResult = scanner.scan(data, length)

            for (signal in scanResult.signals) {
                when (signal) {
                    is TerminalSignal.CommandStarted -> {
                        turnArmed = true
                        notifiedResponseNeededInTurn = false
                        if (commandSubmittedAtMs == null) {
                            commandSubmittedAtMs = nowMs()
                        }
                        outputBytesSinceSubmit = 0
                    }

                    is TerminalSignal.CommandFinished -> {
                        settleJob?.cancel()
                        settleJob = null
                        val shouldFire = isEnabled() && (turnArmed || outputBytesSinceSubmit > 0)
                        if (shouldFire) {
                            explicitSignals += signal
                        } else {
                            turnArmed = false
                            commandSubmittedAtMs = null
                            outputBytesSinceSubmit = 0
                        }
                    }

                    is TerminalSignal.OscNotification -> {
                        settleJob?.cancel()
                        settleJob = null
                        if (isEnabled()) {
                            explicitSignals += signal
                        }
                    }
                }
            }

            if (explicitSignals.isEmpty() && turnArmed && scanResult.printableBytes > 0) {
                lastOutputAtMs = nowMs()
                outputBytesSinceSubmit += scanResult.printableBytes
                scheduleSettleLocked()
            }
        }

        if (explicitSignals.isNotEmpty()) {
            scope.launch {
                for (signal in explicitSignals) {
                    evaluateAndNotify(explicitSignal = signal)
                }
            }
        }
    }

    private fun scheduleSettleLocked() {
        settleJob?.cancel()
        settleJob = null
        if (!isEnabled() || !turnArmed) return
        val delayMs = idleDelayMs()
        if (delayMs <= 0L) return

        settleJob = scope.launch {
            delay(delayMs)
            val canEvaluate = synchronized(lock) {
                isEnabled() && turnArmed
            }
            if (canEvaluate) {
                evaluateAndNotify(explicitSignal = null)
            }
        }
    }

    private suspend fun evaluateAndNotify(explicitSignal: TerminalSignal?) {
        val tail = readTail()
        val kind = classifyPromptKind(tail, explicitSignal) ?: return
        val explicitBody = (explicitSignal as? TerminalSignal.OscNotification)?.body
        val body = explicitBody?.takeIf { it.isNotBlank() } ?: extractOutputSnippet(tail)
        val signature = "${kind.name}:$body"

        val shouldEmit = synchronized(lock) {
            if (!isEnabled()) return@synchronized false
            if (explicitSignal == null && !turnArmed) return@synchronized false
            if (explicitSignal == null && kind == OutputPromptKind.COMPLETED) {
                val submittedAt = commandSubmittedAtMs
                if (submittedAt != null) {
                    val activeSpanMs = (lastOutputAtMs - submittedAt).coerceAtLeast(0L)
                    if (activeSpanMs < MIN_QUICK_ECHO_MS && outputBytesSinceSubmit < MIN_QUICK_ECHO_BYTES) {
                        // Bare Enter prompt echo on a screen with prior scrollback: disarm quietly.
                        turnArmed = false
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                        return@synchronized false
                    }
                }
            }
            if (signature == lastNotifiedSignature) {
                if (kind == OutputPromptKind.COMPLETED) {
                    turnArmed = false
                    commandSubmittedAtMs = null
                    outputBytesSinceSubmit = 0
                }
                return@synchronized false
            }

            when (kind) {
                OutputPromptKind.RESPONSE_NEEDED -> {
                    if (notifiedResponseNeededInTurn && explicitSignal == null) {
                        return@synchronized false
                    }
                    notifiedResponseNeededInTurn = true
                    lastNotifiedSignature = signature
                    true
                }

                OutputPromptKind.COMPLETED -> {
                    turnArmed = false
                    notifiedResponseNeededInTurn = false
                    commandSubmittedAtMs = null
                    outputBytesSinceSubmit = 0
                    lastNotifiedSignature = signature
                    true
                }
            }
        }

        if (shouldEmit) {
            onAlert(kind, formatPromptTitle(label, kind), body)
        }
    }

    fun cancel() {
        synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            turnArmed = false
            notifiedResponseNeededInTurn = false
            commandSubmittedAtMs = null
            outputBytesSinceSubmit = 0
        }
    }
}
