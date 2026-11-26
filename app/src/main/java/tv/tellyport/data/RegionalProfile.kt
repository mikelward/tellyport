package tv.tellyport.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * User-configurable regional profile coordinating VPN backend, mock coordinates,
 * and mapped streaming app packages for zero-touch interception & launching.
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
    val vpnBackend: VpnBackend = VpnBackend.UNCONFIGURED,
    val tunnelName: String? = null,
    val mappedPackages: List<String> = emptyList(),
    val autoLaunchApp: Boolean = true
) {
    /**
     * Convenience accessor for the primary mapped package used when manually activating
     * a profile from the dashboard.
     */
    val primaryTargetPackage: String?
        get() = mappedPackages.firstOrNull { it.isNotBlank() }?.trim()
}

/**
 * Persisted active VPN session rehydrated across process restarts / cold starts.
 */
@Serializable
data class ActiveVpnSession(
    val profileId: String,
    val vpnBackend: VpnBackend,
    val tunnelName: String? = null,
    val networkHandle: Long? = null,
    val activatedAtEpochMs: Long = 0L
)

/**
 * Global Home Launcher auto-disconnect settings.
 */
@Serializable
data class AutoDisconnectSettings(
    val enabled: Boolean = false,
    val gracePeriodSeconds: Int = DEFAULT_GRACE_PERIOD_SECONDS
) {
    companion object {
        const val DEFAULT_GRACE_PERIOD_SECONDS = 60
    }
}

/**
 * Persisted diagnostics recorded by [tv.tellyport.boot.BootRecoveryReceiver] after reboot.
 */
@Serializable
data class BootRecoveryDiagnostics(
    val timestampEpochMs: Long,
    val networkConnected: Boolean,
    val networkType: String,
    val writeSecureSettingsGranted: Boolean,
    val adbWifiEnabled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuStartAttempted: Boolean,
    val message: String
)

/**
 * Persisted wrapper stored in Jetpack DataStore so profiles, active VPN session,
 * auto-disconnect preferences, WG Tunnel key, and boot recovery diagnostics survive restarts.
 */
@Serializable
data class ProfileStore(
    val schemaVersion: Int = 2,
    val initialized: Boolean = false,
    val profiles: List<RegionalProfile> = emptyList(),
    val wgRemoteControlKey: String = "",
    val activeSession: ActiveVpnSession? = null,
    val autoDisconnectSettings: AutoDisconnectSettings = AutoDisconnectSettings(),
    val bootRecoveryDiagnostics: BootRecoveryDiagnostics? = null
)
