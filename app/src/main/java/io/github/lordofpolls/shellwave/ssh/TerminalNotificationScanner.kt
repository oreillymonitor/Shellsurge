package io.github.lordofpolls.shellwave.ssh

import java.io.ByteArrayOutputStream

internal sealed interface TerminalSignal {
    data object CommandStarted : TerminalSignal
    data class CommandFinished(val exitCode: Int?) : TerminalSignal
    data class OscNotification(val title: String?, val body: String) : TerminalSignal
}

internal data class ScanResult(
    val signals: List<TerminalSignal>,
    val printableBytes: Int,
)

private const val MAX_OSC_BYTES = 1024

/**
 * Stateful raw-byte scanner that detects OSC shell-integration (`133;C`, `133;D;<exit>`) and
 * explicit terminal notification sequences (`OSC 9`, `OSC 777;notify`) across chunk boundaries
 * while counting printable non-escape payload bytes.
 */
internal class TerminalNotificationScanner {

    private enum class State { NORMAL, ESC, CSI, OSC, OSC_ESC }

    private var state = State.NORMAL
    private val oscBuffer = ByteArrayOutputStream()
    private var oscTooLong = false

    fun scan(data: ByteArray, length: Int = data.size): ScanResult {
        val limit = length.coerceIn(0, data.size)
        val signals = mutableListOf<TerminalSignal>()
        var printableBytes = 0

        for (i in 0 until limit) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.NORMAL -> {
                    if (b == 0x1B) {
                        state = State.ESC
                    } else if ((b >= 0x20 && b != 0x7F) || (b and 0x80) != 0) {
                        printableBytes++
                    }
                }

                State.ESC -> {
                    state = when (b) {
                        '['.code -> State.CSI
                        ']'.code -> {
                            oscBuffer.reset()
                            oscTooLong = false
                            State.OSC
                        }

                        else -> State.NORMAL
                    }
                }

                State.CSI -> {
                    if (b == 0x1B) {
                        state = State.ESC
                    } else if (b in 0x40..0x7E) {
                        state = State.NORMAL
                    }
                }

                State.OSC -> {
                    when (b) {
                        0x07 -> {
                            finishOsc(signals)
                            state = State.NORMAL
                        }

                        0x1B -> state = State.OSC_ESC
                        else -> appendOscByte(b)
                    }
                }

                State.OSC_ESC -> {
                    when (b) {
                        '\\'.code -> {
                            finishOsc(signals)
                            state = State.NORMAL
                        }

                        0x1B -> {
                            appendOscByte(0x1B)
                            state = State.OSC_ESC
                        }

                        else -> {
                            appendOscByte(0x1B)
                            appendOscByte(b)
                            state = State.OSC
                        }
                    }
                }
            }
        }

        return ScanResult(signals = signals, printableBytes = printableBytes)
    }

    private fun appendOscByte(b: Int) {
        if (oscTooLong) return
        if (oscBuffer.size() < MAX_OSC_BYTES) {
            oscBuffer.write(b)
        } else {
            oscTooLong = true
        }
    }

    private fun finishOsc(signals: MutableList<TerminalSignal>) {
        if (!oscTooLong && oscBuffer.size() > 0) {
            val payload = oscBuffer.toString(Charsets.UTF_8.name())
            parseOscPayload(payload)?.let { signals += it }
        }
        oscBuffer.reset()
        oscTooLong = false
    }

    private fun parseOscPayload(payload: String): TerminalSignal? {
        if (payload == "133;C" || payload.startsWith("133;C;")) {
            return TerminalSignal.CommandStarted
        }
        if (payload == "133;D") {
            return TerminalSignal.CommandFinished(exitCode = null)
        }
        if (payload.startsWith("133;D;")) {
            val rawExit = payload.removePrefix("133;D;").substringBefore(';').trim()
            return TerminalSignal.CommandFinished(exitCode = rawExit.toIntOrNull())
        }
        if (payload.startsWith("9;")) {
            val rest = payload.substring(2)
            // ConEmu / Windows Terminal progress sequences (OSC 9;4;...) are not alert messages.
            if (rest == "4" || rest.startsWith("4;")) return null
            val body = rest.trim()
            return body.takeIf { it.isNotEmpty() }
                ?.let { TerminalSignal.OscNotification(title = null, body = it) }
        }
        if (payload.startsWith("777;notify;")) {
            val rest = payload.removePrefix("777;notify;")
            val sep = rest.indexOf(';')
            val title = if (sep >= 0) rest.substring(0, sep).trim().ifEmpty { null } else null
            val body = (if (sep >= 0) rest.substring(sep + 1) else rest).trim()
            return body.takeIf { it.isNotEmpty() }
                ?.let { TerminalSignal.OscNotification(title = title, body = it) }
        }
        return null
    }
}
