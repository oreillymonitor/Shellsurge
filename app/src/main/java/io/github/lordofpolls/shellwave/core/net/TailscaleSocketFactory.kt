package io.github.lordofpolls.shellwave.core.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

private const val SOCKS5_VERSION = 0x05
private const val SOCKS5_AUTH_USERPASS = 0x02
private const val SOCKS5_USERPASS_VERSION = 0x01
private const val SOCKS5_CMD_CONNECT = 0x01
private const val SOCKS5_ATYP_IPV4 = 0x01
private const val SOCKS5_ATYP_DOMAIN = 0x03
private const val SOCKS5_ATYP_IPV6 = 0x04
private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000

/**
 * Custom [SocketFactory] that tunnels TCP connections through the embedded `tsnet` loopback
 * SOCKS5 proxy using RFC 1929 username/password auth and `ATYP=0x03` unresolved hostnames so
 * Tailscale resolves MagicDNS names and `100.x` peers directly without local DNS lookup.
 */
internal class TailscaleSocketFactory(
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int,
    private val username: String,
    private val password: String,
) : SocketFactory() {

    override fun createSocket(): Socket =
        TailscaleSocks5Socket(proxyHost, proxyPort, username, password)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply {
            connect(InetSocketAddress.createUnresolved(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
        }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress.createUnresolved(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.hostName, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address.hostAddress ?: address.hostName, port, localAddress, localPort)
}

internal class TailscaleSocks5Socket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String,
    private val password: String,
) : Socket() {

    @Volatile
    private var remoteTargetAddress: InetSocketAddress? = null

    override fun getPort(): Int = remoteTargetAddress?.port ?: super.getPort()

    override fun getRemoteSocketAddress(): SocketAddress? =
        remoteTargetAddress ?: super.getRemoteSocketAddress()

    override fun connect(endpoint: SocketAddress) {
        connect(endpoint, DEFAULT_CONNECT_TIMEOUT_MS)
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress
            ?: throw IOException("Unsupported SocketAddress for Tailscale SOCKS5: $endpoint")
        val targetHost = target.hostString?.trim()?.removeSuffix(".")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Missing target host for Tailscale connection")
        val targetPort = target.port
        val connectTimeout = if (timeout > 0) timeout else DEFAULT_CONNECT_TIMEOUT_MS

        super.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeout)
        val previousSoTimeout = soTimeout
        try {
            soTimeout = connectTimeout
            performSocks5Handshake(
                input = getInputStream(),
                output = getOutputStream(),
                username = username,
                password = password,
                targetHost = targetHost,
                targetPort = targetPort,
            )
            remoteTargetAddress = InetSocketAddress.createUnresolved(targetHost, targetPort)
            soTimeout = previousSoTimeout
        } catch (t: Throwable) {
            runCatching { close() }
            if (t is IOException) throw t
            throw IOException("Tailscale SOCKS5 handshake failed: ${t.message}", t)
        }
    }
}

internal fun performSocks5Handshake(
    input: InputStream,
    output: OutputStream,
    username: String,
    password: String,
    targetHost: String,
    targetPort: Int,
) {
    // 1. Greeting: VER=5, NMETHODS=1, METHOD=0x02 (Username/Password)
    output.write(byteArrayOf(SOCKS5_VERSION.toByte(), 0x01, SOCKS5_AUTH_USERPASS.toByte()))
    output.flush()

    val greetReply = readExactBytes(input, 2)
    if ((greetReply[0].toInt() and 0xFF) != SOCKS5_VERSION ||
        (greetReply[1].toInt() and 0xFF) != SOCKS5_AUTH_USERPASS
    ) {
        throw IOException("Tailscale SOCKS5 proxy rejected authentication negotiation")
    }

    // 2. RFC 1929 Username/Password authentication
    val userBytes = username.toByteArray(Charsets.UTF_8)
    val passBytes = password.toByteArray(Charsets.UTF_8)
    if (userBytes.size > 255 || passBytes.size > 255) {
        throw IOException("SOCKS5 credentials exceed 255 bytes")
    }
    val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
    authPacket[0] = SOCKS5_USERPASS_VERSION.toByte()
    authPacket[1] = userBytes.size.toByte()
    System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
    authPacket[2 + userBytes.size] = passBytes.size.toByte()
    System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
    output.write(authPacket)
    output.flush()

    val authReply = readExactBytes(input, 2)
    if ((authReply[1].toInt() and 0xFF) != 0x00) {
        throw IOException("Tailscale SOCKS5 proxy rejected session credentials")
    }

    // 3. CONNECT request with ATYP=0x03 (domain name resolved remotely by tsnet)
    val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
    if (hostBytes.size > 255) {
        throw IOException("Target hostname exceeds 255 bytes")
    }
    val connectPacket = ByteArray(7 + hostBytes.size)
    connectPacket[0] = SOCKS5_VERSION.toByte()
    connectPacket[1] = SOCKS5_CMD_CONNECT.toByte()
    connectPacket[2] = 0x00
    connectPacket[3] = SOCKS5_ATYP_DOMAIN.toByte()
    connectPacket[4] = hostBytes.size.toByte()
    System.arraycopy(hostBytes, 0, connectPacket, 5, hostBytes.size)
    connectPacket[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
    connectPacket[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
    output.write(connectPacket)
    output.flush()

    // 4. CONNECT reply header + BND.ADDR/BND.PORT drain
    val replyHeader = readExactBytes(input, 4)
    val rep = replyHeader[1].toInt() and 0xFF
    if (rep != 0x00) {
        val reason = when (rep) {
            0x04 -> "host unreachable or MagicDNS lookup failed for $targetHost"
            0x05 -> "connection refused by $targetHost:$targetPort"
            else -> "SOCKS5 code $rep"
        }
        throw IOException("Tailscale proxy connection failed ($reason)")
    }
    when (val atyp = replyHeader[3].toInt() and 0xFF) {
        SOCKS5_ATYP_IPV4 -> readExactBytes(input, 4 + 2)
        SOCKS5_ATYP_DOMAIN -> {
            val len = readExactBytes(input, 1)[0].toInt() and 0xFF
            readExactBytes(input, len + 2)
        }
        SOCKS5_ATYP_IPV6 -> readExactBytes(input, 16 + 2)
        else -> throw IOException("Unsupported SOCKS5 reply ATYP: $atyp")
    }
}

private fun readExactBytes(input: InputStream, count: Int): ByteArray {
    val buf = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(buf, offset, count - offset)
        if (read < 0) {
            throw IOException("Unexpected EOF during Tailscale SOCKS5 handshake")
        }
        offset += read
    }
    return buf
}
