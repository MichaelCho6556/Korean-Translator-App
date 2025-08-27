package com.koreantranslator.service

import android.content.Context
import android.net.*
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Professional network state monitoring service
 * Provides real-time network connectivity information for graceful degradation
 */
@Singleton
class NetworkStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _networkState = MutableStateFlow(NetworkState.UNKNOWN)
    val networkState: StateFlow<NetworkState> = _networkState
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    data class NetworkState(
        val isConnected: Boolean,
        val networkType: NetworkType,
        val isMetered: Boolean,
        val signalStrength: SignalStrength = SignalStrength.UNKNOWN
    ) {
        companion object {
            val UNKNOWN = NetworkState(false, NetworkType.NONE, false)
            val DISCONNECTED = NetworkState(false, NetworkType.NONE, false)
        }
    }
    
    enum class NetworkType {
        WIFI, CELLULAR, ETHERNET, VPN, NONE
    }
    
    enum class SignalStrength {
        EXCELLENT, GOOD, FAIR, POOR, UNKNOWN
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "Network available: $network")
            updateNetworkState()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d(TAG, "Network lost: $network")
            updateNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            updateNetworkState()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            updateNetworkState()
        }
    }

    init {
        startMonitoring()
        updateNetworkState() // Get initial state
    }

    private fun startMonitoring() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            
            connectivityManager.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start network monitoring", e)
        }
    }

    private fun updateNetworkState() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val isConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val networkType = determineNetworkType(networkCapabilities)
            val isMetered = connectivityManager.isActiveNetworkMetered
            val signalStrength = determineSignalStrength(networkCapabilities)
            
            val newState = NetworkState(isConnected, networkType, isMetered, signalStrength)
            _networkState.value = newState
            _isConnected.value = isConnected
            
            Log.d(TAG, "Network state updated: $newState")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating network state", e)
            _networkState.value = NetworkState.UNKNOWN
            _isConnected.value = false
        }
    }

    private fun determineNetworkType(capabilities: NetworkCapabilities?): NetworkType {
        return when {
            capabilities == null -> NetworkType.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.NONE
        }
    }

    private fun determineSignalStrength(capabilities: NetworkCapabilities?): SignalStrength {
        return when {
            capabilities == null -> SignalStrength.UNKNOWN
            capabilities.signalStrength >= -50 -> SignalStrength.EXCELLENT
            capabilities.signalStrength >= -70 -> SignalStrength.GOOD
            capabilities.signalStrength >= -80 -> SignalStrength.FAIR
            capabilities.signalStrength > -100 -> SignalStrength.POOR
            else -> SignalStrength.UNKNOWN
        }
    }

    /**
     * Check if network is suitable for expensive operations (like API calls)
     */
    fun isNetworkSuitableForApiCalls(): Boolean {
        val state = _networkState.value
        return state.isConnected && (
            state.networkType == NetworkType.WIFI || 
            (state.networkType == NetworkType.CELLULAR && state.signalStrength != SignalStrength.POOR)
        )
    }

    /**
     * Check if network is metered (cellular data) 
     */
    fun isNetworkMetered(): Boolean {
        return _networkState.value.isMetered
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping network monitoring", e)
        }
    }
}