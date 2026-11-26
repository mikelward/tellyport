package tv.tellyport.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend

/**
 * Abstraction for dispatching Android broadcasts and launching activities, allowing clean
 * unit testing without requiring a real device.
 */
interface IntentDispatcher {
    fun sendBroadcast(intent: Intent)
    fun startActivity(intent: Intent): Boolean
    fun resolveLaunchIntent(packageName: String): Intent?
}

class AndroidIntentDispatcher(
    private val context: Context
) : IntentDispatcher {
    override fun sendBroadcast(intent: Intent) {
        context.sendBroadcast(intent)
    }

    override fun startActivity(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun resolveLaunchIntent(packageName: String): Intent? {
        val pm = context.packageManager
        // Prefer Android TV Leanback launcher intent when present on Google TV Streamer.
        return pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
    }
}

/**
 * Coordinates VPN connect/disconnect broadcasts for Tailscale and WG Tunnel,
 * plus fallback launching of the Tailscale / WG Tunnel UIs.
 */
class VpnController(
    private val dispatcher: IntentDispatcher
) {
    constructor(context: Context) : this(AndroidIntentDispatcher(context.applicationContext))

    /**
     * Builds the explicit broadcast [Intent] to connect Tailscale.
     */
    fun buildTailscaleConnectIntent(): Intent {
        return Intent(ACTION_TAILSCALE_CONNECT).apply {
            `package` = TAILSCALE_PACKAGE
            component = ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER)
        }
    }

    /**
     * Builds the explicit broadcast [Intent] to disconnect Tailscale.
     */
    fun buildTailscaleDisconnectIntent(): Intent {
        return Intent(ACTION_TAILSCALE_DISCONNECT).apply {
            `package` = TAILSCALE_PACKAGE
            component = ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER)
        }
    }

    /**
     * Builds the explicit authenticated broadcast [Intent] to start a WG Tunnel tunnel
     * per https://wgtunnel.com/docs/android/settings/#remote-app-control-with-intents.
     *
     * Returns `null` if [remoteControlKey] is blank because WG Tunnel rejects unauthenticated
     * remote-control requests.
     */
    fun buildWgTunnelConnectIntent(tunnelName: String?, remoteControlKey: String?): Intent? {
        val trimmedKey = remoteControlKey?.trim().orEmpty()
        if (trimmedKey.isEmpty()) return null

        return Intent(ACTION_WG_TUNNEL_CONNECT).apply {
            `package` = WG_TUNNEL_PACKAGE
            component = ComponentName(WG_TUNNEL_PACKAGE, WG_TUNNEL_RECEIVER)
            putExtra(EXTRA_WG_REMOTE_KEY, trimmedKey)
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_WG_TUNNEL_NAME, tunnelName.trim())
            }
        }
    }

    /**
     * Builds the explicit authenticated broadcast [Intent] to stop a WG Tunnel tunnel.
     *
     * Returns `null` if [remoteControlKey] is blank because WG Tunnel rejects unauthenticated
     * remote-control requests.
     */
    fun buildWgTunnelDisconnectIntent(tunnelName: String?, remoteControlKey: String?): Intent? {
        val trimmedKey = remoteControlKey?.trim().orEmpty()
        if (trimmedKey.isEmpty()) return null

        return Intent(ACTION_WG_TUNNEL_DISCONNECT).apply {
            `package` = WG_TUNNEL_PACKAGE
            component = ComponentName(WG_TUNNEL_PACKAGE, WG_TUNNEL_RECEIVER)
            putExtra(EXTRA_WG_REMOTE_KEY, trimmedKey)
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_WG_TUNNEL_NAME, tunnelName.trim())
            }
        }
    }

    /**
     * Builds the connect broadcast [Intent] for the given [backend], or null for [VpnBackend.NONE]
     * or when [VpnBackend.WG_TUNNEL] lacks a configured [wgRemoteControlKey].
     */
    fun buildConnectIntent(
        backend: VpnBackend,
        tunnelOrNodeName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleConnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelConnectIntent(tunnelOrNodeName, wgRemoteControlKey)
            VpnBackend.NONE -> null
        }
    }

    /**
     * Builds the disconnect broadcast [Intent] for the given [backend], or null for [VpnBackend.NONE]
     * or when [VpnBackend.WG_TUNNEL] lacks a configured [wgRemoteControlKey].
     */
    fun buildDisconnectIntent(
        backend: VpnBackend,
        tunnelOrNodeName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleDisconnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelDisconnectIntent(tunnelOrNodeName, wgRemoteControlKey)
            VpnBackend.NONE -> null
        }
    }

    /**
     * Sends the appropriate VPN connect broadcast for [profile]. Returns the dispatched [Intent],
     * or null when [RegionalProfile.vpnBackend] is [VpnBackend.NONE] or missing required WG key.
     */
    fun connect(profile: RegionalProfile, wgRemoteControlKey: String? = null): Intent? {
        val intent = buildConnectIntent(
            backend = profile.vpnBackend,
            tunnelOrNodeName = profile.vpnTunnelOrNodeName,
            wgRemoteControlKey = wgRemoteControlKey
        )
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    /**
     * Sends the disconnect broadcast for a specific [backend] and optional [tunnelOrNodeName].
     */
    fun disconnect(
        backend: VpnBackend,
        tunnelOrNodeName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        val intent = buildDisconnectIntent(backend, tunnelOrNodeName, wgRemoteControlKey)
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    /**
     * Sends disconnect broadcasts for Tailscale and (when [wgRemoteControlKey] is configured)
     * WG Tunnel when the user triggers "Disconnect All".
     */
    fun disconnectAll(
        activeWgTunnelName: String? = null,
        wgRemoteControlKey: String? = null
    ): List<Intent> {
        return disconnectAll(
            wgTunnelNames = listOf(activeWgTunnelName),
            wgRemoteControlKey = wgRemoteControlKey
        )
    }

    /**
     * Sends disconnect broadcasts for Tailscale and every distinct WG Tunnel name in [wgTunnelNames]
     * (when [wgRemoteControlKey] is configured). Passing an empty collection defaults to stopping
     * the primary WG tunnel (`tunnelName` omitted).
     */
    fun disconnectAll(
        wgTunnelNames: Collection<String?>,
        wgRemoteControlKey: String? = null
    ): List<Intent> {
        val normalizedNames = wgTunnelNames
            .map { it?.trim()?.ifEmpty { null } }
            .distinct()
            .ifEmpty { listOf(null) }

        val intents = buildList {
            add(buildTailscaleDisconnectIntent())
            normalizedNames.forEach { tunnelName ->
                buildWgTunnelDisconnectIntent(tunnelName, wgRemoteControlKey)?.let { add(it) }
            }
        }
        intents.forEach { dispatcher.sendBroadcast(it) }
        return intents
    }

    /**
     * Opens the Tailscale app directly so the user can pick or verify the active Exit Node.
     */
    fun openTailscaleApp(): Boolean {
        val intent = dispatcher.resolveLaunchIntent(TAILSCALE_PACKAGE) ?: return false
        return dispatcher.startActivity(intent)
    }

    /**
     * Opens the WG Tunnel app directly so the user can enable Remote App Control and copy the key.
     */
    fun openWgTunnelApp(): Boolean {
        val intent = dispatcher.resolveLaunchIntent(WG_TUNNEL_PACKAGE) ?: return false
        return dispatcher.startActivity(intent)
    }

    /**
     * Launches a target streaming app package (preferring TV Leanback launcher when available).
     */
    fun launchApp(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        val intent = dispatcher.resolveLaunchIntent(trimmed) ?: return false
        return dispatcher.startActivity(intent)
    }

    companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        const val TAILSCALE_RECEIVER = "com.tailscale.ipn.IPNReceiver"
        const val ACTION_TAILSCALE_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
        const val ACTION_TAILSCALE_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"

        const val WG_TUNNEL_PACKAGE = "com.zaneschepke.wireguardautotunnel"
        const val WG_TUNNEL_RECEIVER =
            "com.zaneschepke.wireguardautotunnel.core.broadcast.RemoteControlReceiver"
        const val ACTION_WG_TUNNEL_CONNECT = "com.zaneschepke.wireguardautotunnel.START_TUNNEL"
        const val ACTION_WG_TUNNEL_DISCONNECT = "com.zaneschepke.wireguardautotunnel.STOP_TUNNEL"
        const val EXTRA_WG_TUNNEL_NAME = "tunnelName"
        const val EXTRA_WG_REMOTE_KEY = "key"
    }
}
