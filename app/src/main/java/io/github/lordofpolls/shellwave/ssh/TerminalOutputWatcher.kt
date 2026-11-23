package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_QUICK_ECHO_MS = 1_500L
private const val MIN_QUICK_ECHO_BYTES = 48
private const val MIN_UNPROMPTED_BURST_BYTES = 40

private val PROMPT_LINE_REGEX = Regex("^[\\w.-]+@[\\w.-]+:.*[$#%]\\s*$|^[$#>❯%]\\s*$")
private val DIVIDER_LINE_REGEX = Regex("^[─━═\\-_*~·•]+$")
private val QUESTION_REGEX = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")

internal data class OutputAlertEvent(
    val explicitTitle: String? = null,
    val explicitBody: String? = null,
)

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
    if (lines.isEmpty()) return "New terminal output is ready to inspect."
    val joined = lines.takeLast(3).joinToString("\n")
    return if (joined.length <= maxChars) {
        joined
    } else {
        joined.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"
    }
}

internal fun classifyOutputTitle(label: String, sanitizedTail: String): String {
    val meaningful = sanitizedTail.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !PROMPT_LINE_REGEX.matches(it) }
        .takeLast(6)

    val needsInput = meaningful.any { line ->
        QUESTION_REGEX.containsMatchIn(line) || line.endsWith("?")
    }
    if (needsInput) return "$label · Waiting for input"

    val hasError = meaningful.any { line ->
        line.contains("BUILD FAILED", ignoreCase = true) ||
            line.contains("FAILURES!!!", ignoreCase = true) ||
            line.contains("FAILURE:", ignoreCase = true) ||
            line.contains("error:", ignoreCase = true) ||
            line.contains("fatal:", ignoreCase = true) ||
            line.contains("Exception:", ignoreCase = true)
    }
    if (hasError) return "$label · Command error"

    val hasSuccess = meaningful.any { line ->
        line.contains("BUILD SUCCESSFUL", ignoreCase = true) ||
            line.contains("all tests passed", ignoreCase = true) ||
            line.contains("passed", ignoreCase = true) ||
            line.contains("done", ignoreCase = true) ||
            line.contains("completed", ignoreCase = true) ||
            line.contains("pushed", ignoreCase = true) ||
            line.contains("committed", ignoreCase = true)
    }
    if (hasSuccess) return "$label · Command succeeded"

    return "$label · Output ready"
}

/**
 * Watches one interactive SSH shell's input/output stream to notify immediately on OSC command
 * completion or explicit OSC alerts, or automatically after shell output settles cleanly.
 */
internal class TerminalOutputWatcher(
    private val scope: CoroutineScope,
    private val label: String,
    private val isEnabled: () -> Boolean,
    private val idleDelayMs: () -> Long,
    private val onAlert: (OutputAlertEvent) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val scanner = TerminalNotificationScanner()

    private var commandSubmittedAtMs: Long? = null
    private var lastOutputAtMs: Long = 0L
    private var sawOsc133Start = false
    private var outputBytesSinceSubmit = 0
    private var unpromptedBurstBytes = 0
    private var notifiedForCurrentBurst = false
    private var settleJob: Job? = null

    fun onUserWrite(data: ByteArray) {
        if (!containsCommandSubmit(data)) return
        synchronized(lock) {
            commandSubmittedAtMs = nowMs()
            lastOutputAtMs = 0L
            outputBytesSinceSubmit = 0
            unpromptedBurstBytes = 0
            notifiedForCurrentBurst = false
            settleJob?.cancel()
            settleJob = null
        }
    }

    fun onRawOutput(data: ByteArray, length: Int = data.size) {
        val pendingAlerts = mutableListOf<OutputAlertEvent>()
        var suppressedIdleInChunk = false

        synchronized(lock) {
            val scanResult = scanner.scan(data, length)

            for (signal in scanResult.signals) {
                when (signal) {
                    is TerminalSignal.CommandStarted -> {
                        sawOsc133Start = true
                        if (commandSubmittedAtMs == null) {
                            commandSubmittedAtMs = nowMs()
                        }
                        outputBytesSinceSubmit = 0
                        notifiedForCurrentBurst = false
                    }

                    is TerminalSignal.CommandFinished -> {
                        settleJob?.cancel()
                        settleJob = null
                        val shouldFire = isEnabled() &&
                            (sawOsc133Start || commandSubmittedAtMs != null || outputBytesSinceSubmit > 0)
                        sawOsc133Start = false
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                        unpromptedBurstBytes = 0
                        notifiedForCurrentBurst = true
                        suppressedIdleInChunk = true
                        if (shouldFire) {
                            val title = when {
                                signal.exitCode == 0 -> "$label · Command succeeded"
                                signal.exitCode != null -> "$label · Command failed (exit ${signal.exitCode})"
                                else -> "$label · Command finished"
                            }
                            pendingAlerts += OutputAlertEvent(explicitTitle = title, explicitBody = null)
                        }
                    }

                    is TerminalSignal.OscNotification -> {
                        settleJob?.cancel()
                        settleJob = null
                        sawOsc133Start = false
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                        unpromptedBurstBytes = 0
                        notifiedForCurrentBurst = true
                        suppressedIdleInChunk = true
                        if (isEnabled()) {
                            val title = signal.title?.let { "$label · $it" } ?: "$label · Terminal alert"
                            pendingAlerts += OutputAlertEvent(
                                explicitTitle = title,
                                explicitBody = signal.body,
                            )
                        }
                    }
                }
            }

            if (!suppressedIdleInChunk && scanResult.printableBytes > 0) {
                lastOutputAtMs = nowMs()
                if (commandSubmittedAtMs != null) {
                    outputBytesSinceSubmit += scanResult.printableBytes
                    notifiedForCurrentBurst = false
                    scheduleSettleLocked()
                } else {
                    unpromptedBurstBytes += scanResult.printableBytes
                    if (unpromptedBurstBytes >= MIN_UNPROMPTED_BURST_BYTES) {
                        notifiedForCurrentBurst = false
                        scheduleSettleLocked()
                    }
                }
            }
        }

        for (alert in pendingAlerts) {
            onAlert(alert)
        }
    }

    private fun scheduleSettleLocked() {
        settleJob?.cancel()
        settleJob = null
        if (!isEnabled()) return
        val delayMs = idleDelayMs()
        if (delayMs <= 0L) return

        settleJob = scope.launch {
            delay(delayMs)
            var shouldNotify = false
            synchronized(lock) {
                if (!isEnabled() || notifiedForCurrentBurst) return@synchronized
                val submittedAt = commandSubmittedAtMs
                if (submittedAt != null) {
                    val activeSpanMs = (lastOutputAtMs - submittedAt).coerceAtLeast(0L)
                    if (activeSpanMs < MIN_QUICK_ECHO_MS && outputBytesSinceSubmit < MIN_QUICK_ECHO_BYTES) {
                        // Suppress bare Enter prompt echoes that produced tiny immediate output.
                        commandSubmittedAtMs = null
                        outputBytesSinceSubmit = 0
                        return@synchronized
                    }
                }
                notifiedForCurrentBurst = true
                commandSubmittedAtMs = null
                outputBytesSinceSubmit = 0
                unpromptedBurstBytes = 0
                shouldNotify = true
            }
            if (shouldNotify) {
                onAlert(OutputAlertEvent(explicitTitle = null, explicitBody = null))
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            sawOsc133Start = false
            commandSubmittedAtMs = null
            outputBytesSinceSubmit = 0
            unpromptedBurstBytes = 0
        }
    }
}
