package io.github.lordofpolls.shellwave.core.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

data class TailscaleWebTarget(
    val scheme: String,
    val host: String,
    val port: Int,
    val originalUrl: String,
)

data class TailscaleWebForward(
    val localPort: Int,
    val targetHost: String,
    val targetPort: Int,
)

data class TailscalePingResult(
    val ip: String = "",
    val nodeName: String = "",
    val latencyMs: Double = 0.0,
    val direct: Boolean = false,
    val endpoint: String = "",
    val derpRegionCode: String = "",
    val derpRegionId: Int = 0,
    val peerRelay: String = "",
    val error: String? = null,
) {
    val isSuccess: Boolean
        get() = error.isNullOrBlank()

    val summaryLabel: String
        get() {
            if (!error.isNullOrBlank()) return error
            val ms = latencyMs.roundToInt().coerceAtLeast(0)
            return when {
                direct -> "Direct · $ms ms"
                derpRegionCode.isNotBlank() -> "DERP ${derpRegionCode.uppercase(Locale.ROOT)} · $ms ms"
                derpRegionId > 0 -> "DERP #$derpRegionId · $ms ms"
                peerRelay.isNotBlank() -> "Peer relay · $ms ms"
                else -> "$ms ms"
            }
        }
}

data class WatchedPeerState(
    val watchKey: String,
    val label: String,
    val peerHostname: String,
    val reconnectSessionId: Long? = null,
    val waitForOfflineFirst: Boolean = false,
)

data class WatchedPeerOnlineEvent(
    val peer: TailscalePeer,
    val reconnectSessionId: Long?,
)

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")

/**
 * Parses an IPv4 dotted-quad literal into 4 unsigned bytes, without triggering DNS resolution.
 */
internal fun parseIpv4Bytes(host: String): ByteArray? {
    val clean = host.trim()
    val parts = clean.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for (i in 0 until 4) {
        val part = parts[i]
        if (part.isEmpty() || (part.length > 1 && part[0] == '0')) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        out[i] = value.toByte()
    }
    return out
}

/**
 * Parses an IPv6 literal (with optional `[...]` brackets, optional `%zone`, `::` compression, or
 * trailing IPv4-mapped suffix) into 16 bytes without triggering DNS resolution.
 */
internal fun parseIpv6Bytes(host: String): ByteArray? {
    var clean = host.trim()
    if (clean.startsWith("[") && clean.endsWith("]")) {
        clean = clean.substring(1, clean.length - 1)
    }
    clean = clean.substringBefore('%')
    if (clean.isEmpty() || !clean.contains(':')) return null

    // Handle IPv4-mapped suffix like ::ffff:192.0.2.128
    val lastColon = clean.lastIndexOf(':')
    var ipv4Tail: ByteArray? = null
    if (lastColon >= 0 && clean.substring(lastColon + 1).contains('.')) {
        ipv4Tail = parseIpv4Bytes(clean.substring(lastColon + 1)) ?: return null
        clean = clean.substring(0, lastColon + 1)
    }

    val doubleColonIdx = clean.indexOf("::")
    if (doubleColonIdx >= 0 && clean.indexOf("::", doubleColonIdx + 2) >= 0) {
        return null // at most one "::"
    }

    val leftGroups: List<Int>
    val rightGroups: List<Int>
    if (doubleColonIdx >= 0) {
        val leftPart = clean.substring(0, doubleColonIdx)
        val rightPart = clean.substring(doubleColonIdx + 2)
        leftGroups = if (leftPart.isEmpty()) emptyList() else parseHexGroups(leftPart) ?: return null
        rightGroups = if (rightPart.isEmpty()) emptyList() else parseHexGroups(rightPart) ?: return null
    } else {
        leftGroups = parseHexGroups(clean.trimEnd(':')) ?: return null
        rightGroups = emptyList()
    }

    val totalTargetGroups = if (ipv4Tail != null) 6 else 8
    val explicitGroups = leftGroups.size + rightGroups.size
    if (doubleColonIdx >= 0) {
        if (explicitGroups > totalTargetGroups) return null
    } else {
        if (explicitGroups != totalTargetGroups) return null
    }

    val out = ByteArray(16)
    var pos = 0
    for (g in leftGroups) {
        out[pos++] = ((g ushr 8) and 0xFF).toByte()
        out[pos++] = (g and 0xFF).toByte()
    }
    val zeroGroups = (totalTargetGroups - explicitGroups).coerceAtLeast(0)
    pos += zeroGroups * 2
    for (g in rightGroups) {
        out[pos++] = ((g ushr 8) and 0xFF).toByte()
        out[pos++] = (g and 0xFF).toByte()
    }
    if (ipv4Tail != null) {
        System.arraycopy(ipv4Tail, 0, out, 12, 4)
    }
    return out
}

private fun parseHexGroups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val tokens = part.split(':')
    val groups = ArrayList<Int>(tokens.size)
    for (tok in tokens) {
        if (tok.isEmpty() || tok.length > 4) return null
        val v = tok.toIntOrNull(16) ?: return null
        if (v !in 0..0xFFFF) return null
        groups += v
    }
    return groups
}

/**
 * Returns true if literal [ipString] falls inside [cidr] (`a.b.c.d/len` or `addr/len`), excluding
 * `/0` default routes so exit-node routes never auto-capture arbitrary hosts.
 */
internal fun ipMatchesCidr(ipString: String, cidr: String): Boolean {
    val slashIdx = cidr.indexOf('/')
    if (slashIdx <= 0) return false
    val networkRaw = cidr.substring(0, slashIdx).trim()
    val prefixLen = cidr.substring(slashIdx + 1).trim().toIntOrNull() ?: return false
    if (prefixLen <= 0) return false

    val ip4 = parseIpv4Bytes(ipString)
    val net4 = parseIpv4Bytes(networkRaw)
    if (ip4 != null && net4 != null) {
        return prefixLen <= 32 && bytesMatchPrefix(ip4, net4, prefixLen)
    }

    val ip6 = parseIpv6Bytes(ipString)
    val net6 = parseIpv6Bytes(networkRaw)
    if (ip6 != null && net6 != null) {
        return prefixLen <= 128 && bytesMatchPrefix(ip6, net6, prefixLen)
    }
    return false
}

private fun bytesMatchPrefix(a: ByteArray, b: ByteArray, prefixBits: Int): Boolean {
    val fullBytes = prefixBits / 8
    val remBits = prefixBits % 8
    for (i in 0 until fullBytes) {
        if (a[i] != b[i]) return false
    }
    if (remBits > 0) {
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        if ((a[fullBytes].toInt() and mask) != (b[fullBytes].toInt() and mask)) {
            return false
        }
    }
    return true
}

internal fun findMatchingSubnetPeer(
    hostname: String,
    peers: List<TailscalePeer>,
): Pair<TailscalePeer, String>? {
    val clean = hostname.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (clean.isEmpty()) return null
    if (parseIpv4Bytes(clean) == null && parseIpv6Bytes(clean) == null) return null

    for (peer in peers) {
        if (!peer.online) continue
        for (cidr in peer.primaryRoutes) {
            if (ipMatchesCidr(clean, cidr)) {
                return peer to cidr
            }
        }
    }
    return null
}

internal fun parseTailscaleWebTarget(
    url: String,
    isTailscaleHost: (String) -> Boolean,
): TailscaleWebTarget? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
    if (host.lowercase(Locale.ROOT) in LOOPBACK_HOSTS) return null
    if (!isTailscaleHost(host)) return null
    val port = uri.port.takeIf { it in 1..65535 } ?: if (scheme == "https") 443 else 80
    return TailscaleWebTarget(
        scheme = scheme,
        host = host,
        port = port,
        originalUrl = url.trim(),
    )
}

internal fun rewriteUrlToLoopback(originalUrl: String, localPort: Int): String {
    val uri = runCatching { URI(originalUrl.trim()) }.getOrNull() ?: return "http://127.0.0.1:$localPort"
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: "http"
    val path = uri.rawPath.orEmpty()
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "$scheme://127.0.0.1:$localPort$path$query$fragment"
}

internal fun parseTailscalePingJson(raw: String): TailscalePingResult =
    runCatching {
        val obj = JSONObject(raw)
        TailscalePingResult(
            ip = obj.optString("ip", ""),
            nodeName = obj.optString("nodeName", ""),
            latencyMs = obj.optDouble("latencyMs", 0.0),
            direct = obj.optBoolean("direct", false),
            endpoint = obj.optString("endpoint", ""),
            derpRegionCode = obj.optString("derpRegionCode", ""),
            derpRegionId = obj.optInt("derpRegionId", 0),
            peerRelay = obj.optString("peerRelay", ""),
            error = obj.optString("error", "").trim().takeIf { it.isNotEmpty() },
        )
    }.getOrElse {
        TailscalePingResult(error = it.message ?: "Failed to parse ping result")
    }

internal fun parseActiveTcpForwardsJson(raw: String): List<TailscaleWebForward> =
    runCatching {
        val arr = JSONArray(raw)
        val out = ArrayList<TailscaleWebForward>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val localPort = obj.optInt("localPort", 0)
            val targetHost = obj.optString("targetHost", "").trim()
            val targetPort = obj.optInt("targetPort", 0)
            if (localPort > 0 && targetHost.isNotEmpty() && targetPort > 0) {
                out += TailscaleWebForward(
                    localPort = localPort,
                    targetHost = targetHost,
                    targetPort = targetPort,
                )
            }
        }
        out
    }.getOrDefault(emptyList())

internal fun formatPeerPathBadge(curAddr: String, relay: String): Pair<String, Boolean>? =
    when {
        curAddr.isNotBlank() -> "Direct" to true
        relay.isNotBlank() -> "DERP ${relay.trim().uppercase(Locale.ROOT)}" to false
        else -> null
    }
