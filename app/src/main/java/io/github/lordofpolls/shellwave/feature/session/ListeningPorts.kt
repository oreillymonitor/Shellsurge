package io.github.lordofpolls.shellwave.feature.session

internal data class ListeningPort(
    val port: Int,
    val bindAddress: String,
    val processName: String?,
    val pid: Int?,
)

private const val MARKER_SS = "__SS__"
private const val MARKER_LSOF = "__LSOF__"
private const val MARKER_NETSTAT = "__NETSTAT__"
private const val MARKER_NONE = "__NONE__"

internal val LISTENING_PORTS_COMMAND: String = """
if command -v ss >/dev/null 2>&1; then
  echo "$MARKER_SS"
  ss -H -tlnp 2>/dev/null || ss -H -tln 2>/dev/null
elif command -v lsof >/dev/null 2>&1; then
  echo "$MARKER_LSOF"
  lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null
elif command -v netstat >/dev/null 2>&1; then
  echo "$MARKER_NETSTAT"
  netstat -tlnp 2>/dev/null || netstat -tln 2>/dev/null
else
  echo "$MARKER_NONE"
fi
""".trimIndent()

private enum class PortSource { NONE, SS, LSOF, NETSTAT }

private val SS_PROC_REGEX = Regex("""users:\(\("([^"]+)",pid=(\d+)""")
private val LSOF_ADDR_REGEX = Regex("""(\S+):(\d+)\s+\(LISTEN\)""")
private val NETSTAT_PID_PROG_REGEX = Regex("""(\d+)/(\S+)""")

/**
 * Parses `ss`, `lsof`, or `netstat` TCP LISTEN output into deduplicated unprivileged ports (`1024..65535`).
 */
internal fun parseListeningPorts(stdout: String): List<ListeningPort> {
    var source = PortSource.NONE
    val candidates = mutableListOf<ListeningPort>()

    for (rawLine in stdout.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        when (line) {
            MARKER_SS -> {
                source = PortSource.SS
                continue
            }
            MARKER_LSOF -> {
                source = PortSource.LSOF
                continue
            }
            MARKER_NETSTAT -> {
                source = PortSource.NETSTAT
                continue
            }
            MARKER_NONE -> return emptyList()
        }

        val parsed = when (source) {
            PortSource.SS -> parseSsLine(line)
            PortSource.LSOF -> parseLsofLine(line)
            PortSource.NETSTAT -> parseNetstatLine(line)
            PortSource.NONE -> null
        }
        if (parsed != null && parsed.port in 1024..65535) {
            candidates += parsed
        }
    }

    val byPort = linkedMapOf<Int, ListeningPort>()
    for (entry in candidates) {
        val existing = byPort[entry.port]
        if (existing == null || shouldReplacePortEntry(existing, entry)) {
            byPort[entry.port] = entry
        }
    }
    return byPort.values.sortedBy { it.port }
}

private fun shouldReplacePortEntry(existing: ListeningPort, incoming: ListeningPort): Boolean {
    if (existing.processName == null && incoming.processName != null) return true
    if (existing.processName != null && incoming.processName == null) return false
    return !isLoopbackAddress(existing.bindAddress) && isLoopbackAddress(incoming.bindAddress)
}

private fun isLoopbackAddress(addr: String): Boolean {
    val clean = addr.trim('[', ']')
    return clean == "127.0.0.1" || clean == "::1" || clean == "localhost" || clean == "::ffff:127.0.0.1"
}

private fun parseSsLine(line: String): ListeningPort? {
    val tokens = line.split(Regex("""\s+"""))
    if (tokens.size < 4 || !tokens[0].equals("LISTEN", ignoreCase = true)) return null
    val localToken = tokens[3]
    val (addr, port) = splitHostPort(localToken) ?: return null
    val match = SS_PROC_REGEX.find(line)
    val processName = match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    val pid = match?.groupValues?.get(2)?.toIntOrNull()
    return ListeningPort(port = port, bindAddress = addr, processName = processName, pid = pid)
}

private fun parseLsofLine(line: String): ListeningPort? {
    if (line.startsWith("COMMAND ", ignoreCase = true)) return null
    val addrMatch = LSOF_ADDR_REGEX.find(line) ?: return null
    val addr = addrMatch.groupValues[1]
    val port = addrMatch.groupValues[2].toIntOrNull() ?: return null
    val tokens = line.split(Regex("""\s+"""))
    if (tokens.size < 2) return null
    val processName = tokens[0].replace("\\x20", " ").takeIf { it.isNotBlank() }
    val pid = tokens[1].toIntOrNull()
    return ListeningPort(port = port, bindAddress = addr, processName = processName, pid = pid)
}

private fun parseNetstatLine(line: String): ListeningPort? {
    val tokens = line.split(Regex("""\s+"""))
    if (tokens.size < 6 || !tokens[0].startsWith("tcp", ignoreCase = true)) return null
    val listenIndex = tokens.indexOfFirst { it.equals("LISTEN", ignoreCase = true) }
    if (listenIndex < 4) return null
    val (addr, port) = splitHostPort(tokens[3]) ?: return null
    val pidProg = tokens.getOrNull(listenIndex + 1)?.let { NETSTAT_PID_PROG_REGEX.find(it) }
    val pid = pidProg?.groupValues?.get(1)?.toIntOrNull()
    val processName = pidProg?.groupValues?.get(2)?.takeIf { it.isNotBlank() && it != "-" }
    return ListeningPort(port = port, bindAddress = addr, processName = processName, pid = pid)
}

private fun splitHostPort(endpoint: String): Pair<String, Int>? {
    val lastColon = endpoint.lastIndexOf(':')
    if (lastColon <= 0 || lastColon == endpoint.lastIndex) return null
    val port = endpoint.substring(lastColon + 1).toIntOrNull() ?: return null
    val rawHost = endpoint.substring(0, lastColon).trim('[', ']')
    val cleanHost = rawHost.ifEmpty { "*" }
    return cleanHost to port
}
