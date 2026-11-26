package com.warpmo.app.data

import kotlinx.serialization.Serializable

/**
 * Supported VPN automation backends for a [RegionalProfile].
 */
@Serializable
enum class VpnBackend(val displayName: String) {
    TAILSCALE("Tailscale"),
    WG_TUNNEL("WG Tunnel"),
    NONE("No VPN")
}
