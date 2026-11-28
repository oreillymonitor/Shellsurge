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
    private var statusJsonValue: String,
    private var peersJsonValue: String = "[]",
    private val portValue: Int = 10805,
    private val pingJsonValue: String = """{"ip":"100.64.0.9","nodeName":"workstation","latencyMs":12.3,"direct":true,"endpoint":"1.2.3.4:41641"}""",
) : TailscaleNodeClient {
    var startedDir: String? = null
    var networkChangedCount = 0
    var updateNetworkCount = 0
    var lastDefaultIface: String? = null
    private val activeForwards = mutableListOf<TailscaleWebForward>()
    private var nextForwardPort = 45000

    fun updateSnapshot(statusJson: String, peersJson: String) {
        statusJsonValue = statusJson
        peersJsonValue = peersJson
    }

    override fun start(stateDir: String, hostname: String, authKey: String, controlUrl: String) {
        startedDir = stateDir
    }

    override fun stop() {}
    override fun logout() {}
    override fun notifyNetworkChanged() {
        networkChangedCount++
    }

    override fun updateNetwork(defaultIface: String, gatewayIp: String, interfacesJson: String) {
        updateNetworkCount++
        lastDefaultIface = defaultIface
    }

    override fun proxyPort(): Int = portValue
    override fun proxyUsername(): String = "user"
    override fun proxyPassword(): String = "pass"
    override fun statusJson(): String = statusJsonValue
    override fun peersJson(): String = peersJsonValue
    override fun pingPeerJson(target: String): String = pingJsonValue

    override fun startTcpForward(targetHost: String, targetPort: Int): Int {
        activeForwards.firstOrNull {
            it.targetHost.equals(targetHost, ignoreCase = true) && it.targetPort == targetPort
        }?.let { return it.localPort }
        val port = nextForwardPort++
        activeForwards += TailscaleWebForward(port, targetHost, targetPort)
        return port
    }

    override fun stopTcpForward(localPort: Int) {
        activeForwards.removeAll { it.localPort == localPort }
    }

    override fun stopAllTcpForwards() {
        activeForwards.clear()
    }

    override fun activeTcpForwardsJson(): String =
        activeForwards.joinToString(prefix = "[", postfix = "]") {
            """{"localPort":${it.localPort},"targetHost":"${it.targetHost}","targetPort":${it.targetPort}}"""
        }
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
              "state": "NeedsLogin",
              "authUrl": "https://login.tailscale.com/a/test123",
              "selfHostname": "shellsurge",
              "selfIp": "",
              "tailnetName": "",
              "magicDnsSuffix": "",
              "proxyPort": 41234,
              "lastError": "",
              "detail": "waiting for login"
            }
            """.trimIndent(),
        )
        assertTrue(status.needsLogin)
        assertEquals("https://login.tailscale.com/a/test123", status.authUrl)
        assertEquals("waiting for login", status.detail)
        assertEquals(41234, status.proxyPort)

        val running = parseTailscaleStatusJson(
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
        assertTrue(running.isRunning)
        assertEquals("100.101.102.103", running.selfIp)
        assertEquals("example.github", running.tailnetName)
        assertEquals(41234, running.proxyPort)

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
            peersJsonValue = """[{"id":"p1","hostName":"workstation","dnsName":"workstation.tail1234.ts.net.","ips":["100.64.0.9"],"os":"linux","online":true,"sshHostKeys":true}]""",
            portValue = 44444,
        )
        mgr.nodeFactory = { fakeNode }

        val ok = mgr.ensureRunning(timeoutMs = 1_000L)
        assertTrue(ok.isSuccess)
        assertNotNull(ok.getOrNull())
        mgr.refreshPeers()
        assertTrue(mgr.matchesKnownPeer("workstation"))
        assertTrue(mgr.matchesKnownPeer("WORKSTATION.tail1234.ts.net."))
        assertFalse(mgr.matchesKnownPeer("unknown.example.com"))
        assertTrue(fakeNode.updateNetworkCount >= 1)
        mgr.notifyNetworkChanged()
        assertEquals(1, fakeNode.networkChangedCount)
        assertTrue(fakeNode.updateNetworkCount >= 2)
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

    @Test
    fun `subnet routing, ping, web forwards, and reboot watcher work end-to-end`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("ts-features-test").toFile().apply { deleteOnExit() }
        val mgr = TailscaleManager(TestTailscaleContext(dir))
        val fakeNode = FakeTailscaleNodeClient(
            statusJsonValue = """{"state":"Running","selfIp":"100.64.0.5","proxyPort":44444,"peersVersion":1}""",
            peersJsonValue = """
                [
                  {
                    "id": "router1",
                    "hostName": "home-pi",
                    "dnsName": "home-pi.tail1234.ts.net.",
                    "ips": ["100.64.0.10"],
                    "os": "linux",
                    "online": true,
                    "sshHostKeys": true,
                    "primaryRoutes": ["192.168.1.0/24"],
                    "curAddr": "1.2.3.4:41641",
                    "relay": "nyc"
                  }
                ]
            """.trimIndent(),
            portValue = 44444,
        )
        mgr.nodeFactory = { fakeNode }

        assertTrue(mgr.ensureRunning(timeoutMs = 1_000L).isSuccess)
        mgr.refreshPeers()

        assertTrue(mgr.matchesSubnetRoute("192.168.1.50"))
        assertFalse(mgr.matchesSubnetRoute("10.0.0.5"))
        assertTrue(mgr.isTailscaleRoutableHost("192.168.1.50"))
        assertEquals(1, mgr.advertisedSubnets().size)

        val ping = mgr.pingPeer("home-pi")
        assertTrue(ping.isSuccess)
        assertTrue(ping.direct)
        assertEquals("Direct · 12 ms", ping.summaryLabel)

        val localPort = mgr.startWebForward("home-pi.tail1234.ts.net", 3000, autoStopAfterMs = 0L).getOrThrow()
        assertEquals(1, mgr.webForwards.value.size)
        assertEquals(localPort, mgr.webForwards.value[0].localPort)
        mgr.stopWebForward(localPort)
        assertTrue(mgr.webForwards.value.isEmpty())

        // Watch an online peer (reboot mode): must wait for offline=false then online=true.
        val routerPeer = mgr.peers.value.first()
        mgr.watchPeerUntilOnline(routerPeer, reconnectSessionId = 77L)
        assertTrue(mgr.watchedPeers.value[routerPeer.watchKey]?.waitForOfflineFirst == true)

        fakeNode.updateSnapshot(
            statusJson = """{"state":"Running","selfIp":"100.64.0.5","proxyPort":44444,"peersVersion":2}""",
            peersJson = """[{"id":"router1","hostName":"home-pi","dnsName":"home-pi.tail1234.ts.net.","ips":["100.64.0.10"],"os":"linux","online":false,"sshHostKeys":true}]""",
        )
        mgr.refreshPeers()
        assertTrue(mgr.watchedPeers.value[routerPeer.watchKey]?.waitForOfflineFirst == false)

        fakeNode.updateSnapshot(
            statusJson = """{"state":"Running","selfIp":"100.64.0.5","proxyPort":44444,"peersVersion":3}""",
            peersJson = """[{"id":"router1","hostName":"home-pi","dnsName":"home-pi.tail1234.ts.net.","ips":["100.64.0.10"],"os":"linux","online":true,"sshHostKeys":true}]""",
        )
        mgr.refreshPeers()
        assertFalse(mgr.watchedPeers.value.containsKey(routerPeer.watchKey))

        mgr.stop()
    }
}
