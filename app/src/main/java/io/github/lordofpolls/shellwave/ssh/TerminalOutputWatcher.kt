package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.feature.session.AgentDigestModel
import io.github.lordofpolls.shellwave.terminal.TerminalTextSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_QUICK_ECHO_MS = 1_500L
private const val MIN_QUICK_ECHO_BYTES = 24
private const val PASSIVE_ARM_BYTES = 12
private const val PASSIVE_COMPLETED_BYTES = 16
private const val PASSIVE_WINDOW_RESET_MS = 15_000L
private const val MAX_SETTLE_POLLS = 120

private val PROMPT_LINE_REGEX = Regex(
    "^(?:[\\w.-]+@[\\w.-]+:.*[$#%>❯➜]|(?:~(?:/[\\w./-]*)?|/[\\w./-]+)\\s+.*[$#%>❯➜λ]|[$#>❯➜%λ]|(?:\\[[^\\]]+]|\\([^)]+\\))\\s*[\\w.-]*[$#%>❯➜λ])\\s*$" +
        "|^[>❯➜]\\s+.*$" +
        "|^[\\w.-]+@[\\w.-]+:.*[$#%]\\s+.*$"
)
private val DIVIDER_LINE_REGEX = Regex("^[─━═\\-_*~·•]+$")
private val QUESTION_REGEX = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
private val SPINNER_ONLY_REGEX = Regex("^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏◐◓◑◒\\s]+$")

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
    "running...",
    "esc to interrupt",
    "ctrl+c to interrupt",
)

private val IDLE_TUI_INPUT_MARKERS = listOf(
    "type a message",
    "type your message",
    "ask a follow-up",
    "type a follow-up",
    "send a message",
    "? for shortcuts",
    "shift+tab",
    "context left",
    "ask anything",
    "enter your message",
    "try \"",
)

private val RESPONSE_NEEDED_MARKERS = listOf(
    "do you want",
    "would you like",
    "want me to",
    "should i",
    "shall i",
    "let me know if",
    "let me know how",
    "ready to proceed",
    "ready for me to",
    "how would you like",
    "which option",
    "which one",
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

data class NotificationQuickAction(
    val label: String,
    val sendText: String,
)

enum class AgentTurnState {
    IDLE,
    RUNNING,
    COMPLETED,
    RESPONSE_NEEDED,
}

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

private fun containsSubmitOrQuestionByte(data: ByteArray, length: Int): Boolean {
    val end = length.coerceIn(0, data.size)
    for (i in 0 until end) {
        val b = data[i]
        if (b == '\n'.code.toByte() || b == '?'.code.toByte()) return true
    }
    return false
}

internal fun extractOutputSnippet(sanitizedTail: String, maxChars: Int = 220): String {
    val lines = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
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

private fun hasNumberedMenu(cleanedText: String): Boolean =
    AgentDigestModel.extractNumberedOptions(cleanedText).size >= 2

private fun isResponseNeededText(text: String): Boolean {
    val cleanedLines = TerminalTextSanitizer.stripTuiChrome(text)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (cleanedLines.isEmpty()) return false
    val cleanedBlock = cleanedLines.takeLast(14).joinToString("\n")
    if (hasNumberedMenu(cleanedBlock)) return true

    val window = cleanedLines.takeLast(8)
    if (window.any { line ->
            QUESTION_REGEX.containsMatchIn(line) ||
                RESPONSE_NEEDED_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
    ) {
        return true
    }
    val nonPromptTail = window.filter { !PROMPT_LINE_REGEX.matches(it) }.takeLast(3)
    return nonPromptTail.any { it.endsWith("?") }
}

internal fun extractNotificationQuickActions(
    tail: String,
    kind: OutputPromptKind,
): List<NotificationQuickAction> {
    if (kind != OutputPromptKind.RESPONSE_NEEDED) return emptyList()

    val cleaned = TerminalTextSanitizer.stripTuiChrome(tail)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
        .joinToString("\n")

    val numbered = AgentDigestModel.extractNumberedOptions(cleaned)
    if (numbered.size >= 2) {
        return numbered.take(2).map { opt ->
            val shortLabel = if (opt.label.length <= 18) opt.label else opt.label.take(17).trimEnd() + "…"
            NotificationQuickAction(label = shortLabel, sendText = opt.sendText)
        }
    }

    if (QUESTION_REGEX.containsMatchIn(cleaned)) {
        return listOf(
            NotificationQuickAction(label = "Yes", sendText = "y"),
            NotificationQuickAction(label = "No", sendText = "n"),
        )
    }

    return listOf(
        NotificationQuickAction(label = "Yes", sendText = "yes"),
        NotificationQuickAction(label = "No", sendText = "no"),
    )
}

internal fun screenContentSignature(tail: String): String =
    TerminalTextSanitizer.stripTuiChrome(tail)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
        .takeLast(20)
        .joinToString("\n")

private fun isActivelyRunning(sanitizedTail: String): Boolean {
    val rawLines = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (rawLines.isEmpty()) return false
    return rawLines.takeLast(6).any { line ->
        IN_PROGRESS_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } ||
            SPINNER_ONLY_REGEX.matches(line)
    }
}

/**
 * Classifies terminal output into strictly two actionable notification types:
 * [OutputPromptKind.RESPONSE_NEEDED] or [OutputPromptKind.COMPLETED], or `null` when output is
 * still streaming/in-progress and should not trigger a notification.
 */
internal fun classifyPromptKind(
    sanitizedTail: String,
    explicitSignal: TerminalSignal? = null,
    settledAfterIdle: Boolean = false,
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

    val rawLines = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (rawLines.isEmpty()) return null

    // Check active in-progress indicators (including "esc to interrupt") BEFORE chrome stripping.
    if (isActivelyRunning(sanitizedTail)) return null

    // 1. Check if a response / confirmation / numbered menu / agent question is waiting.
    if (isResponseNeededText(sanitizedTail)) {
        return OutputPromptKind.RESPONSE_NEEDED
    }

    // Check idle TUI harness input box/footer BEFORE chrome stripping drops them.
    val hasIdleTuiPrompt = rawLines.takeLast(6).any { line ->
        IDLE_TUI_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }

    // 2. Check if the command/turn completed (returned to a prompt, printed completion marker,
    // shows an idle TUI input prompt, or produced meaningful output that settled after an idle wait).
    val lines = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (lines.isEmpty()) return null

    val meaningfulLines = lines.filter { !PROMPT_LINE_REGEX.matches(it) }
    if (meaningfulLines.isEmpty()) return null

    val bottomLine = lines.last()
    val returnedToPrompt = PROMPT_LINE_REGEX.matches(bottomLine)
    val hasCompletionMarker = meaningfulLines.takeLast(6).any { line ->
        COMPLETED_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }

    return if (returnedToPrompt || hasIdleTuiPrompt || hasCompletionMarker || settledAfterIdle) {
        OutputPromptKind.COMPLETED
    } else {
        null
    }
}

private enum class EvalOutcome {
    DONE,
    STILL_RUNNING,
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
    private val onStateChange: (AgentTurnState) -> Unit = {},
    private val onRichAlert: ((kind: OutputPromptKind, title: String, body: String, actions: List<NotificationQuickAction>) -> Unit)? = null,
) {
    private val lock = Any()
    private val scanner = TerminalNotificationScanner()

    private var turnArmed = false
    private var passivePending = false
    private var notifiedResponseNeededInTurn = false
    private var lastNotifiedSignature: String? = null
    private var commandSubmittedAtMs: Long? = null
    private var lastOutputAtMs: Long = 0L
    private var outputBytesSinceSubmit = 0
    private var unarmedPrintableBytes = 0
    private var lastUnarmedByteAtMs: Long = 0L
    private var settleJob: Job? = null
    private var currentState: AgentTurnState = AgentTurnState.IDLE

    private fun updateStateLocked(newState: AgentTurnState): Boolean {
        if (currentState == newState) return false
        currentState = newState
        return true
    }

    fun onUserWrite(data: ByteArray) {
        val stateChanged = synchronized(lock) {
            val isSubmit = containsCommandSubmit(data)
            val isInteractiveReply = data.isNotEmpty() && notifiedResponseNeededInTurn
            if (!isSubmit && !isInteractiveReply) return
            turnArmed = true
            passivePending = false
            unarmedPrintableBytes = 0
            notifiedResponseNeededInTurn = false
            lastNotifiedSignature = null
            commandSubmittedAtMs = nowMs()
            lastOutputAtMs = 0L
            outputBytesSinceSubmit = 0
            settleJob?.cancel()
            settleJob = null
            updateStateLocked(AgentTurnState.RUNNING)
        }
        if (stateChanged) onStateChange(AgentTurnState.RUNNING)
    }

    fun onRawOutput(data: ByteArray, length: Int = data.size) {
        val explicitSignals = mutableListOf<TerminalSignal>()
        var notifyRunning = false
        var schedulePassive: Boolean? = null

        synchronized(lock) {
            val scanResult = scanner.scan(data, length)

            for (signal in scanResult.signals) {
                when (signal) {
                    is TerminalSignal.CommandStarted -> {
                        turnArmed = true
                        passivePending = false
                        unarmedPrintableBytes = 0
                        notifiedResponseNeededInTurn = false
                        lastNotifiedSignature = null
                        if (commandSubmittedAtMs == null) {
                            commandSubmittedAtMs = nowMs()
                        }
                        outputBytesSinceSubmit = 0
                        if (updateStateLocked(AgentTurnState.RUNNING)) {
                            notifyRunning = true
                        }
                    }

                    is TerminalSignal.CommandFinished -> {
                        settleJob?.cancel()
                        settleJob = null
                        passivePending = false
                        unarmedPrintableBytes = 0
                        val shouldFire = isEnabled() && (turnArmed || outputBytesSinceSubmit > 0)
                        if (shouldFire) {
                            explicitSignals += signal
                        } else {
                            turnArmed = false
                            commandSubmittedAtMs = null
                            outputBytesSinceSubmit = 0
                            if (currentState == AgentTurnState.RUNNING) {
                                updateStateLocked(AgentTurnState.COMPLETED)
                                explicitSignals += signal
                            }
                        }
                    }

                    is TerminalSignal.OscNotification -> {
                        settleJob?.cancel()
                        settleJob = null
                        passivePending = false
                        unarmedPrintableBytes = 0
                        explicitSignals += signal
                    }
                }
            }

            if (explicitSignals.isEmpty() && scanResult.printableBytes > 0) {
                val now = nowMs()
                if (turnArmed) {
                    lastOutputAtMs = now
                    outputBytesSinceSubmit += scanResult.printableBytes
                    schedulePassive = false
                } else {
                    if (now - lastUnarmedByteAtMs > PASSIVE_WINDOW_RESET_MS) {
                        unarmedPrintableBytes = 0
                    }
                    lastUnarmedByteAtMs = now
                    unarmedPrintableBytes += scanResult.printableBytes
                    val shouldArmPassive = unarmedPrintableBytes >= PASSIVE_ARM_BYTES ||
                        (containsSubmitOrQuestionByte(data, length) && unarmedPrintableBytes >= 4)
                    if (shouldArmPassive) {
                        passivePending = true
                        schedulePassive = true
                    }
                }
            }

            schedulePassive?.let { passive ->
                ensureSettleRunningLocked(passive = passive)
            }
        }

        if (notifyRunning) {
            onStateChange(AgentTurnState.RUNNING)
        }

        if (explicitSignals.isNotEmpty()) {
            scope.launch {
                for (signal in explicitSignals) {
                    evaluateAndNotify(explicitSignal = signal, passive = false, settledAfterIdle = false)
                }
            }
        }
    }

    private fun ensureSettleRunningLocked(passive: Boolean) {
        if (settleJob?.isActive == true) return
        if (!turnArmed && !passivePending) return
        val delayMs = idleDelayMs()
        if (delayMs <= 0L) return

        settleJob = scope.launch {
            var prevSig = screenContentSignature(readTail())
            repeat(MAX_SETTLE_POLLS) {
                delay(delayMs)
                val stillArmed = synchronized(lock) {
                    if (passive) passivePending && !turnArmed else turnArmed
                }
                if (!stillArmed) return@launch

                val tail = readTail()
                val curSig = screenContentSignature(tail)
                if (curSig != prevSig) {
                    prevSig = curSig
                    return@repeat
                }

                val outcome = evaluateAndNotify(
                    explicitSignal = null,
                    passive = passive,
                    settledAfterIdle = !passive,
                    preReadTail = tail,
                )
                if (outcome != EvalOutcome.STILL_RUNNING) return@launch
            }
        }
    }

    private suspend fun evaluateAndNotify(
        explicitSignal: TerminalSignal?,
        passive: Boolean,
        settledAfterIdle: Boolean,
        preReadTail: String? = null,
    ): EvalOutcome {
        val tail = preReadTail ?: readTail()
        val kind = classifyPromptKind(tail, explicitSignal, settledAfterIdle = settledAfterIdle)
        if (kind == null) {
            val running = explicitSignal == null && isActivelyRunning(tail)
            if (!running && passive) {
                synchronized(lock) {
                    passivePending = false
                    unarmedPrintableBytes = 0
                }
            }
            return if (running) EvalOutcome.STILL_RUNNING else EvalOutcome.DONE
        }
        val explicitBody = (explicitSignal as? TerminalSignal.OscNotification)?.body
        val body = explicitBody?.takeIf { it.isNotBlank() } ?: extractOutputSnippet(tail, maxChars = 320)
        val signature = "${kind.name}:$body"

        var stateToEmit: AgentTurnState? = null
        val shouldEmitAlert = synchronized(lock) {
            if (explicitSignal == null) {
                if (passive) {
                    if (!passivePending || turnArmed) return@synchronized false
                    val bytes = unarmedPrintableBytes
                    passivePending = false
                    unarmedPrintableBytes = 0
                    if (kind == OutputPromptKind.COMPLETED && bytes < PASSIVE_COMPLETED_BYTES) {
                        return@synchronized false
                    }
                } else if (!turnArmed) {
                    return@synchronized false
                }
            }

            if (explicitSignal == null && !passive && kind == OutputPromptKind.COMPLETED) {
                val submittedAt = commandSubmittedAtMs
                if (submittedAt != null) {
                    val activeSpanMs = (lastOutputAtMs - submittedAt).coerceAtLeast(0L)
                    if (activeSpanMs < MIN_QUICK_ECHO_MS && outputBytesSinceSubmit < MIN_QUICK_ECHO_BYTES) {
                        // Bare Enter prompt echo on a screen with prior scrollback: disarm quietly.
                        turnArmed = false
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                        if (updateStateLocked(AgentTurnState.IDLE)) {
                            stateToEmit = AgentTurnState.IDLE
                        }
                        return@synchronized false
                    }
                }
            }

            val targetTurnState = when (kind) {
                OutputPromptKind.RESPONSE_NEEDED -> AgentTurnState.RESPONSE_NEEDED
                OutputPromptKind.COMPLETED -> AgentTurnState.COMPLETED
            }
            if ((!passive || targetTurnState == AgentTurnState.RESPONSE_NEEDED) &&
                updateStateLocked(targetTurnState)
            ) {
                stateToEmit = targetTurnState
            }

            if (!isEnabled()) {
                if (kind == OutputPromptKind.COMPLETED && !passive) {
                    turnArmed = false
                    commandSubmittedAtMs = null
                    outputBytesSinceSubmit = 0
                }
                return@synchronized false
            }

            if (signature == lastNotifiedSignature) {
                if (kind == OutputPromptKind.COMPLETED && !passive) {
                    turnArmed = false
                    commandSubmittedAtMs = null
                    outputBytesSinceSubmit = 0
                }
                return@synchronized false
            }

            when (kind) {
                OutputPromptKind.RESPONSE_NEEDED -> {
                    if (notifiedResponseNeededInTurn && explicitSignal == null && !passive) {
                        return@synchronized false
                    }
                    notifiedResponseNeededInTurn = true
                    lastNotifiedSignature = signature
                    true
                }

                OutputPromptKind.COMPLETED -> {
                    if (!passive) {
                        turnArmed = false
                        notifiedResponseNeededInTurn = false
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                    }
                    lastNotifiedSignature = signature
                    true
                }
            }
        }

        stateToEmit?.let(onStateChange)
        if (shouldEmitAlert) {
            val title = formatPromptTitle(label, kind)
            val rich = onRichAlert
            if (rich != null) {
                rich(kind, title, body, extractNotificationQuickActions(tail, kind))
            } else {
                onAlert(kind, title, body)
            }
        }
        return EvalOutcome.DONE
    }

    fun cancel() {
        val changed = synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            turnArmed = false
            passivePending = false
            unarmedPrintableBytes = 0
            notifiedResponseNeededInTurn = false
            lastNotifiedSignature = null
            commandSubmittedAtMs = null
            outputBytesSinceSubmit = 0
            updateStateLocked(AgentTurnState.IDLE)
        }
        if (changed) onStateChange(AgentTurnState.IDLE)
    }
}
