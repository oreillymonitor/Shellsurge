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
private val INLINE_SPINNER_REGEX = Regex("^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏◐◓◑◒✳✻✶✢✽]\\s+\\S.*")
private val AGENT_TOOL_ACTIVITY_REGEX = Regex(
    "^(?:[●•*◦▸▹]\\s*)?(?:Ran|Running|Explored|Exploring|Read|Reading|Edited|Editing|Wrote|Writing|Created|Searched|Searching|Listed|Listing|Viewed|Viewing|Checked|Checking|Called|Calling)\\b.*",
    RegexOption.IGNORE_CASE,
)

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
    "esc to cancel",
    "esc to stop",
    "ctrl+c to interrupt",
    "ctrl+c to cancel",
    "ctrl+c to stop",
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

internal fun compactSessionHostLabel(identity: String): String {
    val trimmed = identity.trim()
    if (trimmed.isEmpty()) return "session"
    val withoutUser = trimmed.substringAfter('@')
    val withoutPort = if (withoutUser.startsWith("[")) {
        withoutUser.substringBeforeLast("]:").let {
            if (it.startsWith("[") && !it.endsWith("]")) "$it]" else it
        }
    } else {
        val lastColon = withoutUser.lastIndexOf(':')
        if (lastColon > 0 && withoutUser.substring(lastColon + 1).all { it.isDigit() }) {
            withoutUser.substring(0, lastColon)
        } else {
            withoutUser
        }
    }
    val cleanHost = withoutPort.trimEnd('.')
    if (cleanHost.endsWith(".ts.net", ignoreCase = true)) {
        val shortName = cleanHost.substringBefore('.')
        if (shortName.isNotBlank()) return shortName
    }
    return cleanHost.ifBlank { trimmed }
}

internal fun formatPromptTitle(label: String, kind: OutputPromptKind): String =
    "${compactSessionHostLabel(label)} · ${kind.titleSuffix}"

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

private val VOLATILE_CLOCK_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

internal fun screenContentSignature(tail: String): String =
    TerminalTextSanitizer.stripTuiChrome(tail)
        .map { line ->
            VOLATILE_CLOCK_REGEX.replace(line, "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
        .takeLast(20)
        .joinToString("\n")

private fun isHardBlockingPrompt(sanitizedTail: String): Boolean {
    val cleanedLines = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (cleanedLines.isEmpty()) return false
    val cleanedBlock = cleanedLines.takeLast(14).joinToString("\n")
    if (hasNumberedMenu(cleanedBlock)) return true
    val tail4 = cleanedLines.takeLast(4)
    return tail4.any { line ->
        QUESTION_REGEX.containsMatchIn(line) ||
            line.contains("password:", ignoreCase = true) ||
            line.contains("passphrase", ignoreCase = true)
    }
}

private fun isToolActivityLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    return AGENT_TOOL_ACTIVITY_REGEX.matches(t) ||
        t.contains("(ctrl+o to expand)", ignoreCase = true)
}

private fun isActivelyRunning(sanitizedTail: String): Boolean {
    val rawLines = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (rawLines.isEmpty()) return false
    if (rawLines.takeLast(6).any { line ->
            IN_PROGRESS_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } ||
                SPINNER_ONLY_REGEX.matches(line) ||
                INLINE_SPINNER_REGEX.matches(line)
        }
    ) {
        return true
    }

    val hasIdlePrompt = rawLines.takeLast(4).any { line ->
        PROMPT_LINE_REGEX.matches(line) ||
            IDLE_TUI_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }
    if (!hasIdlePrompt) {
        val stripped = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
        val lastLine = stripped.lastOrNull()
        if (lastLine != null &&
            isToolActivityLine(lastLine) &&
            stripped.takeLast(3).none { line -> COMPLETED_MARKERS.any { line.contains(it, ignoreCase = true) } } &&
            !isResponseNeededText(sanitizedTail)
        ) {
            return true
        }
    }
    return false
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

    // 1. Active in-progress indicators (including "esc to interrupt") win before chrome stripping.
    if (isActivelyRunning(sanitizedTail)) return null

    // 2. Hard blocking prompts ([y/N], password/passphrase, or true interactive numbered selectors).
    if (isHardBlockingPrompt(sanitizedTail)) {
        return OutputPromptKind.RESPONSE_NEEDED
    }

    // 3. Check idle TUI harness input box/footer BEFORE chrome stripping drops them.
    val hasIdleTuiPrompt = rawLines.takeLast(6).any { line ->
        IDLE_TUI_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }

    val lines = TerminalTextSanitizer.stripTuiChrome(sanitizedTail)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !DIVIDER_LINE_REGEX.matches(it) }
    if (lines.isEmpty()) return null

    val meaningfulLines = lines.filter { !PROMPT_LINE_REGEX.matches(it) }
    if (meaningfulLines.isEmpty()) return null

    val returnedToPrompt = PROMPT_LINE_REGEX.matches(lines.last())
    // An idle TUI chat box or returned shell prompt means the turn finished — conversational prose
    // ending with '?' above an idle prompt is COMPLETED, not a blocking modal.
    if (hasIdleTuiPrompt || returnedToPrompt) {
        return OutputPromptKind.COMPLETED
    }

    // 4. Without an idle prompt, fall back to response-needed phrasing, completion markers, or settle.
    if (isResponseNeededText(sanitizedTail)) {
        return OutputPromptKind.RESPONSE_NEEDED
    }

    val hasCompletionMarker = meaningfulLines.takeLast(6).any { line ->
        COMPLETED_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
    }

    if (!hasCompletionMarker && isToolActivityLine(meaningfulLines.last())) {
        return null
    }

    return if (hasCompletionMarker || settledAfterIdle) {
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
    private val onRichAlert: ((kind: OutputPromptKind, title: String, body: String, actions: List<NotificationQuickAction>, turnEpoch: Long, rawTail: String) -> Unit)? = null,
) {
    private val lock = Any()
    private val scanner = TerminalNotificationScanner()

    private var turnArmed = false
    private var passivePending = false
    private var awaitingInteractiveReply = false
    private var lastNotifiedSignature: String? = null
    private var lastNotifiedScreenSig: String? = null
    private var acknowledgedScreenSig: String? = null
    private var turnEpoch = 0L
    private var commandSubmittedAtMs: Long? = null
    private var lastOutputAtMs: Long = 0L
    private var outputBytesSinceSubmit = 0
    private var unarmedPrintableBytes = 0
    private var lastUnarmedByteAtMs: Long = 0L
    private var settleJob: Job? = null
    private var currentState: AgentTurnState = AgentTurnState.IDLE

    fun currentEpoch(): Long = synchronized(lock) { turnEpoch }

    fun acknowledgeCurrentOutput() {
        synchronized(lock) {
            acknowledgedScreenSig = lastNotifiedScreenSig ?: acknowledgedScreenSig
            passivePending = false
            unarmedPrintableBytes = 0
            if (!turnArmed) {
                settleJob?.cancel()
                settleJob = null
            }
        }
    }

    private fun updateStateLocked(newState: AgentTurnState): Boolean {
        if (currentState == newState) return false
        currentState = newState
        return true
    }

    fun onUserWrite(data: ByteArray) {
        val stateChanged = synchronized(lock) {
            val isSubmit = containsCommandSubmit(data)
            val isInteractiveReply = data.isNotEmpty() && awaitingInteractiveReply
            if (!isSubmit && !isInteractiveReply) return
            turnEpoch++
            turnArmed = true
            passivePending = false
            unarmedPrintableBytes = 0
            awaitingInteractiveReply = false
            lastNotifiedSignature = null
            lastNotifiedScreenSig = null
            acknowledgedScreenSig = null
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
                        turnEpoch++
                        turnArmed = true
                        passivePending = false
                        unarmedPrintableBytes = 0
                        awaitingInteractiveReply = false
                        lastNotifiedSignature = null
                        lastNotifiedScreenSig = null
                        acknowledgedScreenSig = null
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
        val curScreenSig = screenContentSignature(tail)

        var stateToEmit: AgentTurnState? = null
        var alertEpoch = 0L
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

            if (curScreenSig == acknowledgedScreenSig ||
                (explicitSignal == null && (curScreenSig == lastNotifiedScreenSig || signature == lastNotifiedSignature))
            ) {
                passivePending = false
                unarmedPrintableBytes = 0
                if (!passive) {
                    turnArmed = false
                    commandSubmittedAtMs = null
                    outputBytesSinceSubmit = 0
                }
                return@synchronized false
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
                turnArmed = false
                passivePending = false
                unarmedPrintableBytes = 0
                commandSubmittedAtMs = null
                outputBytesSinceSubmit = 0
                return@synchronized false
            }

            turnArmed = false
            passivePending = false
            unarmedPrintableBytes = 0
            commandSubmittedAtMs = null
            outputBytesSinceSubmit = 0
            awaitingInteractiveReply = kind == OutputPromptKind.RESPONSE_NEEDED
            lastNotifiedScreenSig = curScreenSig
            lastNotifiedSignature = signature
            turnEpoch++
            alertEpoch = turnEpoch
            true
        }

        stateToEmit?.let(onStateChange)
        if (shouldEmitAlert) {
            val title = formatPromptTitle(label, kind)
            val rich = onRichAlert
            if (rich != null) {
                rich(kind, title, body, extractNotificationQuickActions(tail, kind), alertEpoch, tail)
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
            turnEpoch++
            turnArmed = false
            passivePending = false
            unarmedPrintableBytes = 0
            awaitingInteractiveReply = false
            lastNotifiedSignature = null
            lastNotifiedScreenSig = null
            acknowledgedScreenSig = null
            commandSubmittedAtMs = null
            outputBytesSinceSubmit = 0
            updateStateLocked(AgentTurnState.IDLE)
        }
        if (changed) onStateChange(AgentTurnState.IDLE)
    }
}
