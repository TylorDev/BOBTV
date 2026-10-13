package com.bobtv.helloworld.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

data class NetworkState(val usable: Boolean, val validated: Boolean, val epoch: Long)

class NetworkMonitor(context: Context, private val onChanged: (NetworkState) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var generation = 0L
    private var registered: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    private var capabilities: NetworkCapabilities? = null
    private var blocked = false
    private var epoch = 0L
    private var state = NetworkState(false, false, 0)
    private fun dispatch(token: Long, action: () -> Unit) {
        handler.post { if (active && token == generation) action() }
    }
    private fun publish(force: Boolean = false) {
        val usable = network != null && !blocked && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = usable && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (force || usable != state.usable || validated != state.validated) {
            if (usable != state.usable) epoch++
            state = NetworkState(usable, validated, epoch)
            onChanged(state)
        }
    }
    fun start() {
        if (active) return
        active = true
        val token = ++generation
        network = connectivity.activeNetwork
        capabilities = network?.let(connectivity::getNetworkCapabilities)
        blocked = false
        publish(true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) = dispatch(token) {
                if (value != network) { network = value; capabilities = null; blocked = false; publish() }
            }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) = dispatch(token) {
                if (value == network) { capabilities = caps; publish() }
            }
            override fun onBlockedStatusChanged(value: Network, valueBlocked: Boolean) = dispatch(token) {
                if (value == network) { blocked = valueBlocked; publish() }
            }
            override fun onLost(value: Network) = dispatch(token) {
                if (value == network) { network = null; capabilities = null; publish() }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        registered = callback
    }
    fun stop() {
        active = false; generation++
        registered?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        registered = null
        handler.removeCallbacksAndMessages(null)
    }
}
