package tv.tellyport.vpn

import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Inferred ownership of an active `TRANSPORT_VPN` network based solely on observable
 * [LinkProperties] (since Android 14 redacts `NetworkCapabilities.getOwnerUid()` for
 * VPN networks owned by external apps).
 */
enum class InferredVpnBackend(val displayName: String) {
    NONE("Disconnected"),
    TAILSCALE("Tailscale (100.64.x)"),
    UNKNOWN_EXTERNAL("External VPN")
}

/**
 * Heuristic that detects Tailscale tunnels via CGNAT `100.64.0.0/10` interface addresses
 * or MagicDNS (`100.100.100.100`), without relying on redacted `ownerUid`.
 */
object VpnBackendHeuristic {

    private val TAILSCALE_MAGIC_DNS: ByteArray = byteArrayOf(100, 100, 100, 100)

    /**
     * Infers the backend from an active VPN network's [LinkProperties].
     */
    fun inferFromLinkProperties(linkProperties: LinkProperties?): InferredVpnBackend {
        if (linkProperties == null) return InferredVpnBackend.UNKNOWN_EXTERNAL
        val addresses = linkProperties.linkAddresses.mapNotNull { it.address }
        val dnsServers = linkProperties.dnsServers
        return inferFromAddresses(addresses, dnsServers)
    }

    /**
     * Pure address-based heuristic suitable for both Android runtime and JVM unit tests.
     */
    fun inferFromAddresses(
        linkAddresses: Collection<InetAddress>,
        dnsServers: Collection<InetAddress> = emptyList()
    ): InferredVpnBackend {
        if (linkAddresses.any(::isTailscaleCgnatIpv4) || dnsServers.any(::isTailscaleMagicDns)) {
            return InferredVpnBackend.TAILSCALE
        }
        return InferredVpnBackend.UNKNOWN_EXTERNAL
    }

    /**
     * Returns `true` if [address] is an IPv4 address within `100.64.0.0/10`
     * (`100.64.0.0` .. `100.127.255.255`), the CGNAT range assigned by Tailscale.
     */
    fun isTailscaleCgnatIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        if (bytes.size != 4) return false
        val firstOctet = bytes[0].toInt() and 0xFF
        val secondOctet = bytes[1].toInt() and 0xFF
        return firstOctet == 100 && (secondOctet and 0xC0) == 64
    }

    /**
     * Returns `true` if [address] is Tailscale's MagicDNS resolver (`100.100.100.100`).
     */
    fun isTailscaleMagicDns(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        return address.address.contentEquals(TAILSCALE_MAGIC_DNS)
    }
}
