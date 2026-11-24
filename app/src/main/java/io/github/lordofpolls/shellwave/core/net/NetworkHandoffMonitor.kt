package io.github.lordofpolls.shellwave.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class NetworkHandoffEvent {
    NONE,
    NETWORK_RECOVERED,
    NETWORK_SWITCHED,
}

/**
 * Pure JVM-testable state machine tracking the active default network handle so initial network
 * observation never triggers a spurious reconnect, stale `onLost` callbacks for superseded
 * interfaces are ignored, and genuine transitions emit recovery vs switch events.
 */
internal class NetworkHandoffTracker {
    private var activeNetworkId: Long? = null
    private var networkLost: Boolean = false

    fun onNetworkValidated(networkId: Long): NetworkHandoffEvent = synchronized(this) {
        if (activeNetworkId == null && !networkLost) {
            activeNetworkId = networkId
            return@synchronized NetworkHandoffEvent.NONE
        }
        if (networkLost) {
            networkLost = false
            activeNetworkId = networkId
            return@synchronized NetworkHandoffEvent.NETWORK_RECOVERED
        }
        if (activeNetworkId != networkId) {
            activeNetworkId = networkId
            return@synchronized NetworkHandoffEvent.NETWORK_SWITCHED
        }
        NetworkHandoffEvent.NONE
    }

    fun onNetworkLost(networkId: Long) = synchronized(this) {
        if (activeNetworkId == null || activeNetworkId == networkId) {
            networkLost = true
            activeNetworkId = null
        }
    }

    fun reset() = synchronized(this) {
        activeNetworkId = null
        networkLost = false
    }
}

/**
 * Observes Android's default network transitions (e.g., Wi-Fi <-> 5G handoffs) while SSH sessions
 * are open, debouncing rapid capability/link flaps before notifying [SessionManager].
 */
internal class NetworkHandoffMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
    private val onHandoff: (NetworkHandoffEvent) -> Unit,
) {
    private val connectivityManager: ConnectivityManager? =
        runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
    private val lock = Any()
    private val tracker = NetworkHandoffTracker()
    private var registered = false
    private var pendingJob: Job? = null
    private var pendingEvent = NetworkHandoffEvent.NONE

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                schedule(tracker.onNetworkValidated(network.networkHandle))
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                schedule(tracker.onNetworkValidated(network.networkHandle))
            }
        }

        override fun onLost(network: Network) {
            tracker.onNetworkLost(network.networkHandle)
        }
    }

    fun start() {
        val manager = connectivityManager ?: return
        synchronized(lock) {
            if (registered) return
            tracker.reset()
            pendingEvent = NetworkHandoffEvent.NONE
            registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        }
    }

    fun stop() {
        val wasRegistered: Boolean
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
            pendingEvent = NetworkHandoffEvent.NONE
            tracker.reset()
            wasRegistered = registered
            registered = false
        }
        if (wasRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
    }

    private fun schedule(event: NetworkHandoffEvent) {
        if (event == NetworkHandoffEvent.NONE) return
        synchronized(lock) {
            pendingEvent = if (
                pendingEvent == NetworkHandoffEvent.NETWORK_SWITCHED ||
                event == NetworkHandoffEvent.NETWORK_SWITCHED
            ) {
                NetworkHandoffEvent.NETWORK_SWITCHED
            } else {
                event
            }
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(debounceMs)
                val toEmit = synchronized(lock) {
                    pendingEvent.also { pendingEvent = NetworkHandoffEvent.NONE }
                }
                if (toEmit != NetworkHandoffEvent.NONE) {
                    onHandoff(toEmit)
                }
            }
        }
    }
}
