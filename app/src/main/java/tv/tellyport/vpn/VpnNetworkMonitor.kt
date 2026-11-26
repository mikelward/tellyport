package tv.tellyport.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import tv.tellyport.data.VpnBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live state of Android's [NetworkCapabilities.TRANSPORT_VPN] tunnel transport.
 */
data class VpnConnectionState(
    val isConnected: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val interfaceName: String? = null,
    val networkHandle: Long? = null,
    val ownerBackend: VpnBackend? = null
)

/**
 * Observes [ConnectivityManager.NetworkCallback] for active VPN transports
 * ([NetworkCapabilities.TRANSPORT_VPN]) and exposes live state as a [StateFlow].
 */
class VpnNetworkMonitor(
    context: Context
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _state = MutableStateFlow(VpnConnectionState())
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            refreshState()
        }

        override fun onLost(network: Network) {
            refreshState()
        }
    }

    /**
     * Begins observing [NetworkCapabilities.TRANSPORT_VPN] changes. Safe to call multiple times.
     */
    fun start() {
        val cm = connectivityManager ?: return
        refreshState()
        if (registered) return
        // Explicitly remove NET_CAPABILITY_NOT_VPN so Android delivers callbacks for VPN networks.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            registered = true
        } catch (_: SecurityException) {
            registered = false
        }
    }

    /**
     * Unregisters the [ConnectivityManager.NetworkCallback].
     */
    fun stop() {
        if (!registered) return
        val cm = connectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        } finally {
            registered = false
        }
    }

    /**
     * Synchronously inspects active networks for [NetworkCapabilities.TRANSPORT_VPN].
     */
    fun refreshState() {
        val cm = connectivityManager ?: run {
            _state.value = VpnConnectionState()
            return
        }

        @Suppress("DEPRECATION")
        val networks = try {
            cm.allNetworks.toList()
        } catch (_: SecurityException) {
            listOfNotNull(cm.activeNetwork)
        }

        val vpnNetwork = networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        if (vpnNetwork != null) {
            val caps = cm.getNetworkCapabilities(vpnNetwork)
            val linkProps = cm.getLinkProperties(vpnNetwork)
            _state.value = VpnConnectionState(
                isConnected = true,
                hasInternetCapability = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                interfaceName = linkProps?.interfaceName,
                networkHandle = vpnNetwork.networkHandle,
                ownerBackend = resolveOwnerBackend(caps)
            )
        } else {
            _state.value = VpnConnectionState(isConnected = false)
        }
    }

    private fun resolveOwnerBackend(caps: NetworkCapabilities?): VpnBackend? {
        if (caps == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ownerUid = caps.ownerUid
        if (ownerUid == Process.INVALID_UID) return null

        return when (ownerUid) {
            getPackageUid(VpnController.TAILSCALE_PACKAGE) -> VpnBackend.TAILSCALE
            getPackageUid(VpnController.WG_TUNNEL_PACKAGE) -> VpnBackend.WG_TUNNEL
            else -> null
        }
    }

    private fun getPackageUid(packageName: String): Int? {
        return try {
            appContext.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
