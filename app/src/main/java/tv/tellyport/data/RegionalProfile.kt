package tv.tellyport.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * User-configurable regional profile coordinating VPN backend, mock coordinates,
 * and optional target streaming app auto-launch.
 */
@Serializable
data class RegionalProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val regionCode: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 15.0,
    val accuracyMeters: Float = 5.0f,
    val vpnBackend: VpnBackend = VpnBackend.TAILSCALE,
    val vpnTunnelOrNodeName: String? = null,
    val targetAppPackage: String? = null,
    val autoLaunchApp: Boolean = true
)

/**
 * Persisted wrapper stored in Jetpack DataStore so first-run seeding can be distinguished
 * from a user intentionally deleting all profiles.
 */
@Serializable
data class ProfileStore(
    val schemaVersion: Int = 1,
    val initialized: Boolean = false,
    val profiles: List<RegionalProfile> = emptyList(),
    val wgRemoteControlKey: String = ""
)
