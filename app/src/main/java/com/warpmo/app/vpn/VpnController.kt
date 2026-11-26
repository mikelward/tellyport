package com.warpmo.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend

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
 * plus fallback launching of the Tailscale UI to select/verify an Exit Node.
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
     * Builds the broadcast [Intent] to connect a WG Tunnel (e.g., UniFi WireGuard tunnel).
     */
    fun buildWgTunnelConnectIntent(tunnelName: String?): Intent {
        return Intent(ACTION_WG_TUNNEL_CONNECT).apply {
            `package` = WG_TUNNEL_PACKAGE
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_WG_TUNNEL_NAME, tunnelName.trim())
            }
        }
    }

    /**
     * Builds the broadcast [Intent] to disconnect a WG Tunnel.
     */
    fun buildWgTunnelDisconnectIntent(tunnelName: String?): Intent {
        return Intent(ACTION_WG_TUNNEL_DISCONNECT).apply {
            `package` = WG_TUNNEL_PACKAGE
            if (!tunnelName.isNullOrBlank()) {
                putExtra(EXTRA_WG_TUNNEL_NAME, tunnelName.trim())
            }
        }
    }

    /**
     * Builds the connect broadcast [Intent] for the given [backend], or null for [VpnBackend.NONE].
     */
    fun buildConnectIntent(backend: VpnBackend, tunnelOrNodeName: String? = null): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleConnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelConnectIntent(tunnelOrNodeName)
            VpnBackend.NONE -> null
        }
    }

    /**
     * Builds the disconnect broadcast [Intent] for the given [backend], or null for [VpnBackend.NONE].
     */
    fun buildDisconnectIntent(backend: VpnBackend, tunnelOrNodeName: String? = null): Intent? {
        return when (backend) {
            VpnBackend.TAILSCALE -> buildTailscaleDisconnectIntent()
            VpnBackend.WG_TUNNEL -> buildWgTunnelDisconnectIntent(tunnelOrNodeName)
            VpnBackend.NONE -> null
        }
    }

    /**
     * Sends the appropriate VPN connect broadcast for [profile]. Returns the dispatched [Intent],
     * or null when [RegionalProfile.vpnBackend] is [VpnBackend.NONE].
     */
    fun connect(profile: RegionalProfile): Intent? {
        val intent = buildConnectIntent(profile.vpnBackend, profile.vpnTunnelOrNodeName)
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    /**
     * Sends the disconnect broadcast for a specific [backend] and optional [tunnelOrNodeName].
     */
    fun disconnect(backend: VpnBackend, tunnelOrNodeName: String? = null): Intent? {
        val intent = buildDisconnectIntent(backend, tunnelOrNodeName)
        if (intent != null) {
            dispatcher.sendBroadcast(intent)
        }
        return intent
    }

    /**
     * Sends disconnect broadcasts for both Tailscale and WG Tunnel (with optional active tunnel name)
     * when the user clicks "Disconnect All".
     */
    fun disconnectAll(activeWgTunnelName: String? = null): List<Intent> {
        val intents = listOf(
            buildTailscaleDisconnectIntent(),
            buildWgTunnelDisconnectIntent(activeWgTunnelName)
        )
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
        const val ACTION_WG_TUNNEL_CONNECT = "com.zaneschepke.wireguardautotunnel.action.CONNECT"
        const val ACTION_WG_TUNNEL_DISCONNECT = "com.zaneschepke.wireguardautotunnel.action.DISCONNECT"
        const val EXTRA_WG_TUNNEL_NAME = "tunnel_name"
    }
}
