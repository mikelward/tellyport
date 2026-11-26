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
 * Coordinates VPN connect/disconnect broadcasts for Tailscale, WG Tunnel, and SOCKS Wrapper,
 * plus fallback launching of VPN / Shizuku / streaming apps.
 */
class VpnController(
    private val dispatcher: IntentDispatcher
) {
    constructor(context: Context) : this(AndroidIntentDispatcher(context.applicationContext))

    fun buildTailscaleConnectIntent(): Intent {
        return Intent(ACTION_TAILSCALE_CONNECT).apply {
            `package` = TAILSCALE_PACKAGE
            component = ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER)
        }
    }

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
     * Builds the broadcast [Intent] to start a SOCKS Wrapper tunnel profile.
     */
    fun buildSocksWrapperConnectIntent(tunnelName: String?): Intent {
        return Intent(ACTION_SOCKS_WRAPPER_CONNECT).apply {
            `package` = SOCKS_WRAPPER_PACKAGE
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_SOCKS_PROFILE_NAME, tunnelName.trim())
            }
        }
    }

    /**
     * Builds the broadcast [Intent] to stop a SOCKS Wrapper tunnel profile.
     */
    fun buildSocksWrapperDisconnectIntent(tunnelName: String?): Intent {
        return Intent(ACTION_SOCKS_WRAPPER_DISCONNECT).apply {
            `package` = SOCKS_WRAPPER_PACKAGE
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_SOCKS_PROFILE_NAME, tunnelName.trim())
            }
        }
    }

    fun buildConnectIntent(
        backend: VpnBackend,
        tunnelName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleConnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelConnectIntent(tunnelName, wgRemoteControlKey)
            VpnBackend.SOCKS_WRAPPER -> buildSocksWrapperConnectIntent(tunnelName)
            VpnBackend.NONE, VpnBackend.UNCONFIGURED -> null
        }
    }

    fun buildDisconnectIntent(
        backend: VpnBackend,
        tunnelName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleDisconnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelDisconnectIntent(tunnelName, wgRemoteControlKey)
            VpnBackend.SOCKS_WRAPPER -> buildSocksWrapperDisconnectIntent(tunnelName)
            VpnBackend.NONE, VpnBackend.UNCONFIGURED -> null
        }
    }

    fun connect(profile: RegionalProfile, wgRemoteControlKey: String? = null): Intent? {
        val intent = buildConnectIntent(
            backend = profile.vpnBackend,
            tunnelName = profile.tunnelName,
            wgRemoteControlKey = wgRemoteControlKey
        )
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    fun disconnect(
        backend: VpnBackend,
        tunnelName: String? = null,
        wgRemoteControlKey: String? = null
    ): Intent? {
        val intent = buildDisconnectIntent(backend, tunnelName, wgRemoteControlKey)
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    /**
     * Sends disconnect broadcasts to Tailscale and (when [wgRemoteControlKey] is configured)
     * WG Tunnel — plus optionally SOCKS Wrapper — so whichever app owns an unattributed
     * `TRANSPORT_VPN` tunnel tears it down cleanly.
     */
    fun disconnectAll(
        activeWgTunnelName: String? = null,
        wgRemoteControlKey: String? = null,
        includeSocksWrapper: Boolean = false
    ): List<Intent> {
        return disconnectAll(
            wgTunnelNames = listOf(activeWgTunnelName),
            wgRemoteControlKey = wgRemoteControlKey,
            includeSocksWrapper = includeSocksWrapper
        )
    }

    fun disconnectAll(
        wgTunnelNames: Collection<String?>,
        wgRemoteControlKey: String? = null,
        includeSocksWrapper: Boolean = false
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
            if (includeSocksWrapper) {
                add(buildSocksWrapperDisconnectIntent(null))
            }
        }
        intents.forEach { dispatcher.sendBroadcast(it) }
        return intents
    }

    fun openTailscaleApp(): Boolean {
        val intent = dispatcher.resolveLaunchIntent(TAILSCALE_PACKAGE) ?: return false
        return dispatcher.startActivity(intent)
    }

    fun openWgTunnelApp(): Boolean {
        val intent = dispatcher.resolveLaunchIntent(WG_TUNNEL_PACKAGE) ?: return false
        return dispatcher.startActivity(intent)
    }

    fun openShizukuApp(): Boolean {
        val intent = dispatcher.resolveLaunchIntent(SHIZUKU_PACKAGE) ?: return false
        return dispatcher.startActivity(intent)
    }

    /**
     * Launches a target streaming app package (preferring TV Leanback launcher when available).
     * When [clearTask] is true (e.g., after intercepting and force-stopping an app), sets
     * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` so the app restarts cleanly.
     */
    fun launchApp(packageName: String, clearTask: Boolean = false): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        val intent = dispatcher.resolveLaunchIntent(trimmed) ?: return false
        if (clearTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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

        const val SOCKS_WRAPPER_PACKAGE = "tv.tellyport.socks"
        const val ACTION_SOCKS_WRAPPER_CONNECT = "tv.tellyport.socks.action.CONNECT"
        const val ACTION_SOCKS_WRAPPER_DISCONNECT = "tv.tellyport.socks.action.DISCONNECT"
        const val EXTRA_SOCKS_PROFILE_NAME = "profile_name"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
