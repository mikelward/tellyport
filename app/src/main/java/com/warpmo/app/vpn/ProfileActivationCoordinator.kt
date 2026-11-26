package com.warpmo.app.vpn

import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Step-by-step progress reported during profile activation.
 */
sealed class ActivationProgress {
    data object Idle : ActivationProgress()
    data class InProgress(val profileId: String, val message: String) : ActivationProgress()
    data class Active(val profileId: String, val summary: String) : ActivationProgress()
    data class Warning(val profileId: String, val message: String) : ActivationProgress()
}

/**
 * Coordinates the multi-step activation sequence when a regional profile is triggered:
 * 1. Send VPN connect broadcast (if configured).
 * 2. Start [com.warpmo.app.service.MockLocationService] with the profile's coordinates.
 * 3. Wait up to 8 seconds for `TRANSPORT_VPN` to become active (or proceed immediately if `NONE`).
 * 4. Launch `targetAppPackage` if `autoLaunchApp` is true.
 */
class ProfileActivationCoordinator(
    private val vpnController: VpnController,
    private val vpnConnectedFlow: StateFlow<Boolean>,
    private val startMockLocation: (RegionalProfile) -> Unit,
    private val stopMockLocation: () -> Unit,
    private val vpnWaitTimeoutMs: Long = DEFAULT_VPN_WAIT_TIMEOUT_MS
) {
    private val _progress = MutableStateFlow<ActivationProgress>(ActivationProgress.Idle)
    val progress: StateFlow<ActivationProgress> = _progress.asStateFlow()

    private var lastActivatedProfile: RegionalProfile? = null

    suspend fun activate(profile: RegionalProfile): ActivationProgress {
        lastActivatedProfile = profile

        // Step 1: Trigger VPN connect intent if configured.
        if (profile.vpnBackend != VpnBackend.NONE) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Connecting ${profile.vpnBackend.displayName}..."
            )
            vpnController.connect(profile)
        }

        // Step 2: Start MockLocationService with the profile's coordinates.
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Starting mock location for ${profile.name}..."
        )
        startMockLocation(profile)

        // Step 3: Wait up to 8 seconds for TRANSPORT_VPN if a VPN backend is configured.
        var vpnTimedOut = false
        if (profile.vpnBackend != VpnBackend.NONE && !vpnConnectedFlow.value) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Waiting up to ${vpnWaitTimeoutMs / 1000}s for VPN tunnel..."
            )
            val connected = withTimeoutOrNull(vpnWaitTimeoutMs) {
                vpnConnectedFlow.first { it }
            }
            vpnTimedOut = connected != true
        }

        // Step 4: Launch targetAppPackage if autoLaunchApp is enabled.
        val targetPackage = profile.targetAppPackage?.trim().orEmpty()
        var launchWarning: String? = null
        if (profile.autoLaunchApp && targetPackage.isNotEmpty()) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Launching $targetPackage..."
            )
            val launched = vpnController.launchApp(targetPackage)
            if (!launched) {
                launchWarning = "App not installed or launchable: $targetPackage"
            }
        }

        val finalState = when {
            vpnTimedOut && launchWarning != null -> ActivationProgress.Warning(
                profileId = profile.id,
                message = "VPN did not report active within ${vpnWaitTimeoutMs / 1000}s; $launchWarning"
            )

            vpnTimedOut -> ActivationProgress.Warning(
                profileId = profile.id,
                message = "Mock location active for ${profile.name}, but VPN did not report connected within ${vpnWaitTimeoutMs / 1000}s."
            )

            launchWarning != null -> ActivationProgress.Warning(
                profileId = profile.id,
                message = "Profile ${profile.name} active. $launchWarning"
            )

            else -> ActivationProgress.Active(
                profileId = profile.id,
                summary = buildString {
                    append("${profile.name} active")
                    if (profile.vpnBackend != VpnBackend.NONE) {
                        append(" • ${profile.vpnBackend.displayName}")
                    }
                    if (profile.autoLaunchApp && targetPackage.isNotEmpty()) {
                        append(" • Launched $targetPackage")
                    }
                }
            )
        }

        _progress.value = finalState
        return finalState
    }

    /**
     * Stops mock location injection and sends VPN disconnect broadcasts.
     */
    fun deactivateAll() {
        stopMockLocation()
        val wgTunnelName = lastActivatedProfile
            ?.takeIf { it.vpnBackend == VpnBackend.WG_TUNNEL }
            ?.vpnTunnelOrNodeName
        vpnController.disconnectAll(activeWgTunnelName = wgTunnelName)
        lastActivatedProfile = null
        _progress.value = ActivationProgress.Idle
    }

    companion object {
        const val DEFAULT_VPN_WAIT_TIMEOUT_MS: Long = 8_000L
    }
}
