package tv.tellyport.data

import kotlinx.serialization.Serializable

/**
 * Supported VPN automation backends for a [RegionalProfile].
 *
 * Profiles default to [UNCONFIGURED] so users explicitly choose which city uses external
 * [TAILSCALE] automation (at most one profile app-wide) versus [WG_TUNNEL], [SOCKS_WRAPPER],
 * or [NONE].
 */
@Serializable
enum class VpnBackend(val displayName: String) {
    UNCONFIGURED("Unconfigured"),
    TAILSCALE("Tailscale"),
    WG_TUNNEL("WG Tunnel"),
    SOCKS_WRAPPER("SOCKS Wrapper"),
    NONE("No VPN")
}
