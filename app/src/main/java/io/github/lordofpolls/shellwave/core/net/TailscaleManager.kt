package io.github.lordofpolls.shellwave.core.net

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.core.prefs.TailscalePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

private const val TAG = "TailscaleManager"
private const val STATUS_POLL_INTERVAL_MS = 1_200L

data class TailscaleStatus(
    val state: String = "Stopped",
    val authUrl: String? = null,
    val selfHostname: String? = null,
    val selfIp: String? = null,
    val tailnetName: String? = null,
    val magicDnsSuffix: String? = null,
    val proxyPort: Int = 0,
    val lastError: String? = null,
    val libraryAvailable: Boolean = true,
) {
    val isRunning: Boolean
        get() = state.equals("Running", ignoreCase = true) && proxyPort > 0

    val needsLogin: Boolean
        get() = state.equals("NeedsLogin", ignoreCase = true)

    val isActive: Boolean
        get() = !state.equals("Stopped", ignoreCase = true)
}

data class TailscalePeer(
    val id: String,
    val hostName: String,
    val dnsName: String,
    val ips: List<String>,
    val os: String,
    val online: Boolean,
    val sshHostKeys: Boolean,
) {
    val primaryAddress: String
        get() = dnsName.removeSuffix(".").ifBlank {
            ips.firstOrNull { !it.contains(':') }.orEmpty().ifBlank {
                ips.firstOrNull().orEmpty().ifBlank { hostName }
            }
        }
}

internal interface TailscaleNodeClient {
    fun start(stateDir: String, hostname: String, authKey: String, controlUrl: String)
    fun stop()
    fun logout()
    fun notifyNetworkChanged()
    fun proxyPort(): Int
    fun proxyUsername(): String
    fun proxyPassword(): String
    fun statusJson(): String
    fun peersJson(): String
}

internal class GomobileTailscaleNodeClient private constructor(
    private val nodeInstance: Any,
) : TailscaleNodeClient {

    private val cls = nodeInstance.javaClass

    override fun start(stateDir: String, hostname: String, authKey: String, controlUrl: String) {
        invokeUnwrapped(
            "start",
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
            stateDir,
            hostname,
            authKey,
            controlUrl,
        )
    }

    override fun stop() {
        invokeUnwrapped("stop", emptyArray())
    }

    override fun logout() {
        invokeUnwrapped("logout", emptyArray())
    }

    override fun notifyNetworkChanged() {
        runCatching { invokeUnwrapped("notifyNetworkChanged", emptyArray()) }
    }

    override fun proxyPort(): Int =
        (invokeUnwrapped("proxyPort", emptyArray()) as? Number)?.toInt() ?: 0

    override fun proxyUsername(): String =
        invokeUnwrapped("proxyUsername", emptyArray()) as? String ?: ""

    override fun proxyPassword(): String =
        invokeUnwrapped("proxyPassword", emptyArray()) as? String ?: ""

    override fun statusJson(): String =
        invokeAnyName(listOf("statusJSON", "statusJson")) as? String ?: "{}"

    override fun peersJson(): String =
        invokeAnyName(listOf("peersJSON", "peersJson")) as? String ?: "[]"

    private fun invokeAnyName(names: List<String>): Any? {
        for (name in names) {
            val method = runCatching { cls.getMethod(name) }.getOrNull() ?: continue
            return try {
                method.invoke(nodeInstance)
            } catch (e: InvocationTargetException) {
                throw (e.targetException ?: e)
            }
        }
        return null
    }

    private fun invokeUnwrapped(name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val method = cls.getMethod(name, *paramTypes)
        return try {
            method.invoke(nodeInstance, *args)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    companion object {
        private const val BRIDGE_CLASS = "io.github.lordofpolls.shellwave.tailscale.tsbridge.Tsbridge"

        fun tryCreate(): TailscaleNodeClient? =
            try {
                val bridgeClass = Class.forName(BRIDGE_CLASS)
                val newNode = bridgeClass.getMethod("newNode")
                val node = newNode.invoke(null) ?: return null
                GomobileTailscaleNodeClient(node)
            } catch (e: Throwable) {
                Log.w(TAG, "Embedded Tailscale native library not present: ${e.message}")
                null
            }
    }
}

internal fun parseTailscaleStatusJson(raw: String): TailscaleStatus {
    return runCatching {
        val obj = JSONObject(raw)
        TailscaleStatus(
            state = obj.optString("state", "Stopped").ifBlank { "Stopped" },
            authUrl = obj.optString("authUrl", "").trim().takeIf { it.isNotEmpty() },
            selfHostname = obj.optString("selfHostname", "").trim().takeIf { it.isNotEmpty() },
            selfIp = obj.optString("selfIp", "").trim().takeIf { it.isNotEmpty() },
            tailnetName = obj.optString("tailnetName", "").trim().takeIf { it.isNotEmpty() },
            magicDnsSuffix = obj.optString("magicDnsSuffix", "").trim().takeIf { it.isNotEmpty() },
            proxyPort = obj.optInt("proxyPort", 0),
            lastError = obj.optString("lastError", "").trim().takeIf { it.isNotEmpty() },
            libraryAvailable = true,
        )
    }.getOrElse {
        TailscaleStatus(state = "Stopped", lastError = it.message, libraryAvailable = true)
    }
}

internal fun parseTailscalePeersJson(raw: String): List<TailscalePeer> {
    return runCatching {
        val array = JSONArray(raw)
        val result = ArrayList<TailscalePeer>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val ipsArray = obj.optJSONArray("ips")
            val ips = mutableListOf<String>()
            if (ipsArray != null) {
                for (j in 0 until ipsArray.length()) {
                    ipsArray.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let { ips += it }
                }
            }
            result += TailscalePeer(
                id = obj.optString("id", ""),
                hostName = obj.optString("hostName", ""),
                dnsName = obj.optString("dnsName", ""),
                ips = ips,
                os = obj.optString("os", ""),
                online = obj.optBoolean("online", false),
                sshHostKeys = obj.optBoolean("sshHostKeys", false),
            )
        }
        result
    }.getOrDefault(emptyList())
}

/**
 * Manages the embedded userspace Tailscale (`tsnet`) lifecycle, interactive OAuth/AuthKey state,
 * MagicDNS loopback [SocketFactory], and live Tailnet peer directory without Android `VpnService`.
 */
@Singleton
open class TailscaleManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @VisibleForTesting
    internal var nodeFactory: () -> TailscaleNodeClient? = { GomobileTailscaleNodeClient.tryCreate() }

    @Volatile
    private var activeNode: TailscaleNodeClient? = null
    private var pollJob: Job? = null
    private var lastLoadedRunningPeers = false

    private val _status = MutableStateFlow(TailscaleStatus())
    open val status: StateFlow<TailscaleStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<TailscalePeer>>(emptyList())
    open val peers: StateFlow<List<TailscalePeer>> = _peers.asStateFlow()

    private fun stateDir(): File = File(context.filesDir, "tsnet")

    open suspend fun start(authKey: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (activeNode != null && _status.value.isRunning) {
                return@withContext Result.success(Unit)
            }
            val node = activeNode ?: nodeFactory() ?: run {
                val unavailable = TailscaleStatus(
                    state = "Unavailable",
                    lastError = "Embedded Tailscale library is not bundled in this build.",
                    libraryAvailable = false,
                )
                _status.value = unavailable
                return@withContext Result.failure(IllegalStateException(unavailable.lastError))
            }

            return@withContext runCatching {
                TailscalePreferences.setEnabled(context, true)
                _status.value = _status.value.copy(state = "Starting", lastError = null, libraryAvailable = true)
                val hostname = TailscalePreferences.getHostname(context)
                val controlUrl = TailscalePreferences.getControlUrl(context)
                node.start(stateDir().absolutePath, hostname, authKey.trim(), controlUrl)
                activeNode = node
                lastLoadedRunningPeers = false
                syncSnapshot(node)
                startPolling(node)
            }.onFailure { err ->
                _status.value = _status.value.copy(
                    state = "Stopped",
                    lastError = err.message ?: "Failed to start Tailscale node",
                )
            }
        }
    }

    open suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            pollJob?.cancel()
            pollJob = null
            lastLoadedRunningPeers = false
            runCatching { activeNode?.stop() }
            activeNode = null
            _peers.value = emptyList()
            _status.value = TailscaleStatus(state = "Stopped", libraryAvailable = _status.value.libraryAvailable)
        }
    }

    open suspend fun logout() = withContext(Dispatchers.IO) {
        mutex.withLock {
            pollJob?.cancel()
            pollJob = null
            lastLoadedRunningPeers = false
            runCatching { activeNode?.logout() }
            activeNode = null
            runCatching { stateDir().deleteRecursively() }
            TailscalePreferences.setEnabled(context, false)
            _peers.value = emptyList()
            _status.value = TailscaleStatus(state = "Stopped", libraryAvailable = _status.value.libraryAvailable)
        }
    }

    open suspend fun refreshPeers() = withContext(Dispatchers.IO) {
        val node = activeNode ?: return@withContext
        val raw = runCatching { node.peersJson() }.getOrNull() ?: return@withContext
        _peers.value = parseTailscalePeersJson(raw)
    }

    open suspend fun ensureRunning(timeoutMs: Long = 20_000L): Result<SocketFactory> =
        withContext(Dispatchers.IO) {
            socketFactory()?.let { return@withContext Result.success(it) }

            val startResult = start()
            if (startResult.isFailure) {
                return@withContext Result.failure(startResult.exceptionOrNull()!!)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                activeNode?.let { syncSnapshot(it) }
                socketFactory()?.let { return@withContext Result.success(it) }
                val current = _status.value
                if (current.needsLogin) {
                    return@withContext Result.failure(
                        IllegalStateException("Tailscale sign-in required — open Settings → Tailscale to sign in."),
                    )
                }
                delay(250L)
            }
            val lastErr = _status.value.lastError
            Result.failure(
                IllegalStateException(
                    lastErr ?: "Timed out waiting for embedded Tailscale node (${_status.value.state}).",
                ),
            )
        }

    open fun isRunning(): Boolean = _status.value.isRunning

    open fun socketFactory(): SocketFactory? {
        val node = activeNode ?: return null
        val snap = _status.value
        if (!snap.isRunning) return null
        val port = runCatching { node.proxyPort() }.getOrDefault(0).takeIf { it > 0 } ?: snap.proxyPort
        val user = runCatching { node.proxyUsername() }.getOrDefault("")
        val pass = runCatching { node.proxyPassword() }.getOrDefault("")
        if (port <= 0 || user.isEmpty() || pass.isEmpty()) return null
        return TailscaleSocketFactory(proxyPort = port, username = user, password = pass)
    }

    open fun notifyNetworkChanged() {
        activeNode?.notifyNetworkChanged()
    }

    private fun startPolling(node: TailscaleNodeClient) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && activeNode === node) {
                val snap = syncSnapshot(node)
                if (snap.isRunning && !lastLoadedRunningPeers) {
                    lastLoadedRunningPeers = true
                    refreshPeers()
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun syncSnapshot(node: TailscaleNodeClient): TailscaleStatus {
        val raw = runCatching { node.statusJson() }.getOrNull() ?: return _status.value
        val parsed = parseTailscaleStatusJson(raw)
        _status.value = parsed
        return parsed
    }
}
