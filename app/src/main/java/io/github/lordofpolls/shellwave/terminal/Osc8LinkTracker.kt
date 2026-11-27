package io.github.lordofpolls.shellwave.terminal

import java.io.ByteArrayOutputStream

data class Osc8Hyperlink(
    val uri: String,
    val label: String,
    val id: String? = null,
    val labelSegments: List<String> = listOf(label).filter { it.isNotBlank() },
)

private const val MAX_OSC_BYTES = 4096
private const val MAX_LABEL_BYTES = 512
private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")

/**
 * Stateful wire-level scanner that captures OSC 8 hyperlinks (`ESC ] 8 ; params ; uri BEL/ST`)
 * across chunk boundaries without altering vendored `terminal-core/`.
 */
class Osc8LinkTracker(private val maxEntries: Int = 64) {

    private enum class State { NORMAL, ESC, ESC_CHARSET, CSI, OSC, OSC_ESC, STRING_IGN, STRING_IGN_ESC }

    private val lock = Any()
    private var state = State.NORMAL
    private val oscBuffer = ByteArrayOutputStream()
    private var oscTooLong = false

    private var activeUri: String? = null
    private var activeId: String? = null
    private val segmentBuffer = ByteArrayOutputStream()
    private val activeSegments = mutableListOf<String>()

    private val recorded = ArrayDeque<Osc8Hyperlink>()

    fun scan(data: ByteArray, length: Int = data.size) {
        val limit = length.coerceIn(0, data.size)
        synchronized(lock) {
            for (i in 0 until limit) {
                val b = data[i].toInt() and 0xFF
                when (state) {
                    State.NORMAL -> {
                        when {
                            b == 0x1B -> state = State.ESC
                            b == '\r'.code || b == '\n'.code -> flushActiveSegmentLocked()
                            (b >= 0x20 && b != 0x7F) || (b and 0x80) != 0 -> appendLabelByteLocked(b)
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
                            'P'.code, '_'.code, '^'.code, 'X'.code -> State.STRING_IGN
                            '('.code, ')'.code, '*'.code, '+'.code -> State.ESC_CHARSET
                            else -> State.NORMAL
                        }
                    }

                    State.ESC_CHARSET -> state = State.NORMAL

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
                                finishOscLocked()
                                state = State.NORMAL
                            }
                            0x1B -> state = State.OSC_ESC
                            else -> appendOscByteLocked(b)
                        }
                    }

                    State.OSC_ESC -> {
                        when (b) {
                            '\\'.code -> {
                                finishOscLocked()
                                state = State.NORMAL
                            }
                            0x1B -> {
                                appendOscByteLocked(0x1B)
                                state = State.OSC_ESC
                            }
                            else -> {
                                appendOscByteLocked(0x1B)
                                appendOscByteLocked(b)
                                state = State.OSC
                            }
                        }
                    }

                    State.STRING_IGN -> {
                        when (b) {
                            0x07 -> state = State.NORMAL
                            0x1B -> state = State.STRING_IGN_ESC
                        }
                    }

                    State.STRING_IGN_ESC -> {
                        state = when (b) {
                            '\\'.code -> State.NORMAL
                            0x1B -> State.STRING_IGN_ESC
                            else -> State.STRING_IGN
                        }
                    }
                }
            }
        }
    }

    fun snapshot(): List<Osc8Hyperlink> = synchronized(lock) { recorded.toList() }

    fun clear() {
        synchronized(lock) {
            state = State.NORMAL
            oscBuffer.reset()
            oscTooLong = false
            activeUri = null
            activeId = null
            segmentBuffer.reset()
            activeSegments.clear()
            recorded.clear()
        }
    }

    private fun appendOscByteLocked(b: Int) {
        if (oscTooLong) return
        if (oscBuffer.size() < MAX_OSC_BYTES) {
            oscBuffer.write(b)
        } else {
            oscTooLong = true
        }
    }

    private fun appendLabelByteLocked(b: Int) {
        if (activeUri == null) return
        if (segmentBuffer.size() < MAX_LABEL_BYTES) {
            segmentBuffer.write(b)
        }
    }

    private fun flushActiveSegmentLocked() {
        if (activeUri == null || segmentBuffer.size() == 0) {
            segmentBuffer.reset()
            return
        }
        val text = segmentBuffer.toString(Charsets.UTF_8.name()).trim()
        segmentBuffer.reset()
        if (text.isNotEmpty() && activeSegments.size < 16) {
            activeSegments += text
        }
    }

    private fun finishOscLocked() {
        if (!oscTooLong && oscBuffer.size() > 0) {
            val payload = oscBuffer.toString(Charsets.UTF_8.name())
            if (payload.startsWith("8;")) {
                handleOsc8Locked(payload.substring(2))
            }
        }
        oscBuffer.reset()
        oscTooLong = false
    }

    private fun handleOsc8Locked(rest: String) {
        val sep = rest.indexOf(';')
        if (sep < 0) return
        val params = rest.substring(0, sep)
        val uri = rest.substring(sep + 1).trim()
        val id = parseIdParam(params)

        commitActiveLinkLocked()
        if (uri.isNotEmpty()) {
            activeUri = uri
            activeId = id
        }
    }

    private fun commitActiveLinkLocked() {
        flushActiveSegmentLocked()
        val uri = activeUri
        val id = activeId
        val segments = activeSegments.toList()
        activeUri = null
        activeId = null
        segmentBuffer.reset()
        activeSegments.clear()

        if (uri == null || !URI_SCHEME_REGEX.matches(uri)) return

        val head = recorded.firstOrNull()
        val mergedSegments = if (head != null && head.uri == uri && head.id == id) {
            (head.labelSegments + segments).distinct()
        } else {
            segments.distinct()
        }
        val label = mergedSegments.joinToString(" ").trim().ifEmpty { uri }

        recorded.removeAll { it.uri == uri }
        recorded.addFirst(
            Osc8Hyperlink(
                uri = uri,
                label = label,
                id = id,
                labelSegments = mergedSegments.ifEmpty { listOf(label) },
            )
        )
        while (recorded.size > maxEntries) {
            recorded.removeLast()
        }
    }

    private fun parseIdParam(params: String): String? {
        if (params.isEmpty()) return null
        for (part in params.split(':')) {
            if (part.startsWith("id=")) {
                return part.removePrefix("id=").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
