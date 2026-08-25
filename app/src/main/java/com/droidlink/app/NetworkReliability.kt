package com.droidlink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

data class DroidLinkNetworkState(
    val available: Boolean,
    val validated: Boolean,
    val transport: String
)

object NetworkRecoveryPolicy {
    const val NORMAL_RECOVERY_GRACE_MS = 3_000L
    const val RESTART_COOLDOWN_MS = 10_000L
    const val MAX_ICE_RESTARTS_PER_DISCONNECT = 2
    const val FAILED_SESSION_GRACE_MS = 30_000L

    fun canRestart(attempts: Int, millisecondsSinceLastRestart: Long, networkAvailable: Boolean) =
        networkAvailable && attempts < MAX_ICE_RESTARTS_PER_DISCONNECT &&
            millisecondsSinceLastRestart >= RESTART_COOLDOWN_MS
}

class NetworkStateMonitor(
    context: Context,
    private val onStateChanged: (DroidLinkNetworkState, Boolean) -> Unit
) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false
    private var activeNetwork: Network? = null
    private var lastState = DroidLinkNetworkState(false, false, "NONE")

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish(network, capabilities)
        override fun onLost(network: Network) {
            if (network != activeNetwork) return
            activeNetwork = null
            val previous = lastState
            lastState = DroidLinkNetworkState(false, false, "NONE")
            Log.w("DroidLink", "NETWORK_STATE: unavailable previousTransport=${previous.transport}")
            onStateChanged(lastState, true)
        }
    }

    fun start() {
        if (registered) return
        registered = true
        connectivity.registerDefaultNetworkCallback(callback)
        val current = connectivity.activeNetwork
        if (current != null) publish(current) else onStateChanged(lastState, false)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        activeNetwork = null
    }

    private fun publish(network: Network, supplied: NetworkCapabilities? = null) {
        if (!registered) return
        val capabilities = supplied ?: connectivity.getNetworkCapabilities(network) ?: return
        val state = DroidLinkNetworkState(
            available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
        )
        val changed = activeNetwork != null && activeNetwork != network
        if (activeNetwork == network && lastState == state) return
        activeNetwork = network
        lastState = state
        Log.d("DroidLink", "NETWORK_STATE: available=${state.available} validated=${state.validated} transport=${state.transport} changed=$changed")
        onStateChanged(state, changed)
    }
}
