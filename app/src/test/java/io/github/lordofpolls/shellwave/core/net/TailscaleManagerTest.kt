package io.github.lordofpolls.shellwave.core.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.lordofpolls.shellwave.core.prefs.FakeSharedPreferences
import io.github.lordofpolls.shellwave.core.prefs.TailscalePreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeTailscaleNodeClient(
    private val statusJsonValue: String,
    private val peersJsonValue: String = "[]",
    private val portValue: Int = 10805,
) : TailscaleNodeClient {
    var startedDir: String? = null
    var networkChangedCount = 0

    override fun start(stateDir: String, hostname: String, authKey: String, controlUrl: String) {
        startedDir = stateDir
    }

    override fun stop() {}
    override fun logout() {}
    override fun notifyNetworkChanged() {
        networkChangedCount++
    }

    override fun proxyPort(): Int = portValue
    override fun proxyUsername(): String = "user"
    override fun proxyPassword(): String = "pass"
    override fun statusJson(): String = statusJsonValue
    override fun peersJson(): String = peersJsonValue
}

private class TestTailscaleContext(private val dir: File) : ContextWrapper(null) {
    private val prefs = FakeSharedPreferences()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

class TailscaleManagerTest {

    @Test
    fun `looksLikeTailscaleHost matches ts net domains and CGNAT 100_64 range`() {
        assertTrue(TailscalePreferences.looksLikeTailscaleHost("devbox.tailnet.ts.net"))
        assertTrue(TailscalePreferences.looksLikeTailscaleHost("devbox.tailnet.ts.net."))
        assertTrue(TailscalePreferences.looksLikeTailscaleHost("100.64.0.1"))
        assertTrue(TailscalePreferences.looksLikeTailscaleHost("100.127.255.254"))

        assertFalse(TailscalePreferences.looksLikeTailscaleHost("100.63.255.254"))
        assertFalse(TailscalePreferences.looksLikeTailscaleHost("100.128.0.1"))
        assertFalse(TailscalePreferences.looksLikeTailscaleHost("192.168.1.10"))
        assertFalse(TailscalePreferences.looksLikeTailscaleHost("example.com"))
    }

    @Test
    fun `parseTailscaleStatusJson and parseTailscalePeersJson decode bridge snapshots`() {
        val status = parseTailscaleStatusJson(
            """
            {
              "state": "Running",
              "authUrl": "",
              "selfHostname": "shellsurge",
              "selfIp": "100.101.102.103",
              "tailnetName": "example.github",
              "magicDnsSuffix": "tail1234.ts.net",
              "proxyPort": 41234,
              "lastError": ""
            }
            """.trimIndent(),
        )
        assertTrue(status.isRunning)
        assertEquals("100.101.102.103", status.selfIp)
        assertEquals("example.github", status.tailnetName)
        assertEquals(41234, status.proxyPort)

        val peers = parseTailscalePeersJson(
            """
            [
              {
                "id": "n1",
                "hostName": "workstation",
                "dnsName": "workstation.tail1234.ts.net.",
                "ips": ["100.64.1.2", "fd7a::1"],
                "os": "linux",
                "online": true,
                "sshHostKeys": true
              }
            ]
            """.trimIndent(),
        )
        assertEquals(1, peers.size)
        assertEquals("workstation.tail1234.ts.net", peers[0].primaryAddress)
        assertTrue(peers[0].online)
        assertTrue(peers[0].sshHostKeys)
    }

    @Test
    fun `ensureRunning returns SocketFactory when Running and fails fast on NeedsLogin`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("ts-mgr-test").toFile().apply { deleteOnExit() }
        val mgr = TailscaleManager(TestTailscaleContext(dir))
        val fakeNode = FakeTailscaleNodeClient(
            statusJsonValue = """{"state":"Running","selfIp":"100.64.0.5","proxyPort":44444}""",
            portValue = 44444,
        )
        mgr.nodeFactory = { fakeNode }

        val ok = mgr.ensureRunning(timeoutMs = 1_000L)
        assertTrue(ok.isSuccess)
        assertNotNull(ok.getOrNull())
        mgr.notifyNetworkChanged()
        assertEquals(1, fakeNode.networkChangedCount)
        mgr.stop()

        val loginMgr = TailscaleManager(TestTailscaleContext(dir))
        loginMgr.nodeFactory = {
            FakeTailscaleNodeClient(
                statusJsonValue = """{"state":"NeedsLogin","authUrl":"https://login.tailscale.com/a/abc","proxyPort":44445}""",
            )
        }
        val needsLogin = loginMgr.ensureRunning(timeoutMs = 1_000L)
        assertTrue(needsLogin.isFailure)
        assertTrue(needsLogin.exceptionOrNull()?.message?.contains("sign-in", ignoreCase = true) == true)
        loginMgr.stop()
    }
}
