package io.github.lordofpolls.shellwave.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TailscaleSocketFactoryTest {

    @Test
    fun `connects via SOCKS5 userpass and sends unresolved MagicDNS hostname with ATYP 0x03`() {
        val capturedHost = AtomicReference<String>()
        val capturedPort = AtomicReference<Int>()
        val capturedAuth = AtomicReference<Pair<String, String>>()

        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            server.use { ss ->
                ss.accept().use { sock ->
                    val input = DataInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()

                    // 1. Greeting: VER=5, NMETHODS=1, METHOD=2
                    val ver = input.readUnsignedByte()
                    val nMethods = input.readUnsignedByte()
                    val methods = ByteArray(nMethods)
                    input.readFully(methods)
                    assertEquals(0x05, ver)
                    assertTrue(methods.contains(0x02.toByte()))
                    output.write(byteArrayOf(0x05, 0x02))
                    output.flush()

                    // 2. RFC 1929 User/Pass
                    assertEquals(0x01, input.readUnsignedByte())
                    val uLen = input.readUnsignedByte()
                    val uBytes = ByteArray(uLen)
                    input.readFully(uBytes)
                    val pLen = input.readUnsignedByte()
                    val pBytes = ByteArray(pLen)
                    input.readFully(pBytes)
                    capturedAuth.set(String(uBytes) to String(pBytes))
                    output.write(byteArrayOf(0x01, 0x00))
                    output.flush()

                    // 3. CONNECT request with ATYP=0x03
                    assertEquals(0x05, input.readUnsignedByte())
                    assertEquals(0x01, input.readUnsignedByte())
                    assertEquals(0x00, input.readUnsignedByte())
                    assertEquals(0x03, input.readUnsignedByte())
                    val hostLen = input.readUnsignedByte()
                    val hostBytes = ByteArray(hostLen)
                    input.readFully(hostBytes)
                    val port = input.readUnsignedShort()
                    capturedHost.set(String(hostBytes))
                    capturedPort.set(port)

                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                    output.flush()

                    val msg = ByteArray(4)
                    input.readFully(msg)
                    output.write(msg)
                    output.flush()
                }
            }
        }

        val factory = TailscaleSocketFactory(
            proxyHost = "127.0.0.1",
            proxyPort = server.localPort,
            username = "tsuser",
            password = "tspassword",
        )

        factory.createSocket().use { socket ->
            socket.connect(InetSocketAddress.createUnresolved("devbox.tail1234.ts.net", 22), 2_000)
            socket.getOutputStream().write("PING".toByteArray())
            socket.getOutputStream().flush()
            val reply = ByteArray(4)
            DataInputStream(socket.getInputStream()).readFully(reply)
            assertEquals("PING", String(reply))
        }
        worker.join(2_000)

        assertEquals("tsuser" to "tspassword", capturedAuth.get())
        assertEquals("devbox.tail1234.ts.net", capturedHost.get())
        assertEquals(22, capturedPort.get())
    }

    @Test(expected = java.io.IOException::class)
    fun `rejects bad credentials cleanly`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            server.use { ss ->
                ss.accept().use { sock ->
                    val input = DataInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()
                    input.readUnsignedByte()
                    val n = input.readUnsignedByte()
                    input.readFully(ByteArray(n))
                    output.write(byteArrayOf(0x05, 0x02))
                    output.flush()

                    input.readUnsignedByte()
                    val u = input.readUnsignedByte()
                    input.readFully(ByteArray(u))
                    val p = input.readUnsignedByte()
                    input.readFully(ByteArray(p))
                    output.write(byteArrayOf(0x01, 0x01))
                    output.flush()
                }
            }
        }

        val out = ByteArrayOutputStream()
        assertEquals(0, out.size())
        val factory = TailscaleSocketFactory("127.0.0.1", server.localPort, "bad", "creds")
        try {
            factory.createSocket().use { socket ->
                socket.connect(InetSocketAddress.createUnresolved("devbox", 22), 2_000)
            }
        } finally {
            worker.join(2_000)
        }
    }
}
