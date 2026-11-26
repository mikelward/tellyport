package tv.tellyport.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Live state of Android's [NetworkCapabilities.TRANSPORT_VPN] tunnel transport.
 *
 * Uses [LinkProperties]-based [inferredBackend] instead of `NetworkCapabilities.getOwnerUid()`,
 * which Android 14 redacts (`Process.INVALID_UID`) for external VPN apps.
 */
data class VpnConnectionState(
    val isConnected: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val interfaceName: String? = null,
    val networkHandle: Long? = null,
    val inferredBackend: InferredVpnBackend = InferredVpnBackend.NONE,
    val ipv4Addresses: List<String> = emptyList()
)

/**
 * Observes [ConnectivityManager.NetworkCallback] for active VPN transports
 * ([NetworkCapabilities.TRANSPORT_VPN]) and exposes live state as a [StateFlow].
 */
class VpnNetworkMonitor(
    context: Context,
    private val onVpnLostCallback: (() -> Unit)? = null
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

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
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
     * Synchronously inspects active networks for [NetworkCapabilities.TRANSPORT_VPN]
     * and inspects [LinkProperties] to infer Tailscale (`100.64.0.0/10` / `100.100.100.100`)
     * vs an unattributed external VPN.
     */
    fun refreshState(): VpnConnectionState {
        val previous = _state.value
        val cm = connectivityManager ?: run {
            val disconnected = VpnConnectionState()
            _state.value = disconnected
            if (previous.isConnected) onVpnLostCallback?.invoke()
            return disconnected
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

        val next = if (vpnNetwork != null) {
            val caps = cm.getNetworkCapabilities(vpnNetwork)
            val linkProps = cm.getLinkProperties(vpnNetwork)
            val ipv4List = linkProps?.linkAddresses
                ?.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                .orEmpty()
            VpnConnectionState(
                isConnected = true,
                hasInternetCapability = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                interfaceName = linkProps?.interfaceName,
                networkHandle = vpnNetwork.networkHandle,
                inferredBackend = VpnBackendHeuristic.inferFromLinkProperties(linkProps),
                ipv4Addresses = ipv4List
            )
        } else {
            VpnConnectionState(
                isConnected = false,
                inferredBackend = InferredVpnBackend.NONE
            )
        }

        _state.value = next
        if (previous.isConnected && !next.isConnected) {
            onVpnLostCallback?.invoke()
        }
        return next
    }
}
