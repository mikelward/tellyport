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
 * 1. Coordinate teardown of any conflicting VPN backend/tunnel, then send VPN connect broadcast.
 * 2. Start [com.warpmo.app.service.MockLocationService] with the profile's coordinates.
 * 3. Wait up to 8 seconds for the requested VPN to become active (or proceed immediately if `NONE`).
 * 4. Launch `targetAppPackage` if `autoLaunchApp` is true and the requested VPN is ready.
 */
class ProfileActivationCoordinator(
    private val vpnController: VpnController,
    private val vpnStateFlow: StateFlow<VpnConnectionState>,
    private val startMockLocation: (RegionalProfile) -> Unit,
    private val stopMockLocation: () -> Unit,
    private val wgRemoteControlKeyProvider: () -> String = { "" },
    private val vpnWaitTimeoutMs: Long = DEFAULT_VPN_WAIT_TIMEOUT_MS
) {
    private val _progress = MutableStateFlow<ActivationProgress>(ActivationProgress.Idle)
    val progress: StateFlow<ActivationProgress> = _progress.asStateFlow()

    private var activeVpnBackend: VpnBackend? = null
    private var activeWgTunnelName: String? = null

    suspend fun activate(profile: RegionalProfile): ActivationProgress {
        val wgKey = wgRemoteControlKeyProvider().trim()

        // If the underlying VPN transport dropped since our last activation, clear tracked backend.
        if (!vpnStateFlow.value.isConnected) {
            activeVpnBackend = null
            activeWgTunnelName = null
        }

        // Validate required authentication key before attempting WG Tunnel remote control.
        if (profile.vpnBackend == VpnBackend.WG_TUNNEL && wgKey.isEmpty()) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "WG Tunnel remote control key is not configured. Copy the key from WG Tunnel → Settings → Remote App Control and save it in Setup & Diagnostics."
            )
            _progress.value = warning
            return warning
        }

        // Fast path for profiles that do not use a VPN backend.
        if (profile.vpnBackend == VpnBackend.NONE) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Starting mock location for ${profile.name}..."
            )
            startMockLocation(profile)
            return finishWithOptionalLaunch(profile, vpnReady = true)
        }

        val initialVpnState = vpnStateFlow.value
        val alreadySatisfied = isAlreadySatisfyingRequestedVpn(initialVpnState, profile)

        if (alreadySatisfied) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Connecting ${profile.vpnBackend.displayName}..."
            )
            vpnController.connect(profile, wgKey)

            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Starting mock location for ${profile.name}..."
            )
            startMockLocation(profile)

            activeVpnBackend = profile.vpnBackend
            activeWgTunnelName = normalizedWgTunnel(profile)
            return finishWithOptionalLaunch(profile, vpnReady = true)
        }

        // Start mock location so coordinates are ready while the VPN connects/switches.
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Starting mock location for ${profile.name}..."
        )
        startMockLocation(profile)

        val previousHandle = initialVpnState.networkHandle
        val hadExistingVpn = initialVpnState.isConnected
        var sawDisconnect = !hadExistingVpn

        if (hadExistingVpn) {
            // Explicitly tear down the old backend/tunnel so we do not treat the old VPN
            // connection as readiness for the newly requested backend.
            val backendToDisconnect = resolveConflictingBackend(initialVpnState, profile)
            val tunnelToDisconnect = if (backendToDisconnect == VpnBackend.WG_TUNNEL) {
                activeWgTunnelName
            } else {
                null
            }

            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Disconnecting previous VPN before starting ${profile.vpnBackend.displayName}..."
            )
            if (backendToDisconnect != null) {
                vpnController.disconnect(
                    backend = backendToDisconnect,
                    tunnelOrNodeName = tunnelToDisconnect,
                    wgRemoteControlKey = wgKey
                )
            }

            val teardownBudgetMs = minOf(vpnWaitTimeoutMs / 2, DEFAULT_TEARDOWN_TIMEOUT_MS)
            val teardownState = withTimeoutOrNull(teardownBudgetMs) {
                vpnStateFlow.first { state ->
                    !state.isConnected ||
                        (previousHandle != null && state.networkHandle != previousHandle) ||
                        (state.ownerBackend == profile.vpnBackend && !isSwitchingWgTunnel(profile))
                }
            }
            if (teardownState != null && !teardownState.isConnected) {
                sawDisconnect = true
            }
        }

        // Send connect broadcast for the requested backend.
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Connecting ${profile.vpnBackend.displayName}..."
        )
        vpnController.connect(profile, wgKey)

        // Wait for the requested VPN to become active.
        val connectBudgetMs = if (hadExistingVpn) {
            maxOf(vpnWaitTimeoutMs - minOf(vpnWaitTimeoutMs / 2, DEFAULT_TEARDOWN_TIMEOUT_MS), 1_000L)
        } else {
            vpnWaitTimeoutMs
        }

        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Waiting up to ${vpnWaitTimeoutMs / 1000}s for ${profile.vpnBackend.displayName}..."
        )

        val connectedState = withTimeoutOrNull(connectBudgetMs) {
            vpnStateFlow.first { state ->
                if (!state.isConnected) {
                    sawDisconnect = true
                    return@first false
                }
                isRequestedVpnReady(
                    state = state,
                    profile = profile,
                    hadExistingVpn = hadExistingVpn,
                    sawDisconnect = sawDisconnect,
                    previousHandle = previousHandle
                )
            }
        }

        val vpnReady = connectedState != null
        if (vpnReady) {
            activeVpnBackend = profile.vpnBackend
            activeWgTunnelName = normalizedWgTunnel(profile)
        }

        return finishWithOptionalLaunch(profile, vpnReady = vpnReady)
    }

    private fun finishWithOptionalLaunch(
        profile: RegionalProfile,
        vpnReady: Boolean
    ): ActivationProgress {
        val targetPackage = profile.targetAppPackage?.trim().orEmpty()

        // Do not launch the streaming app if the requested VPN did not become ready.
        if (!vpnReady) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "Mock location active for ${profile.name}, but ${profile.vpnBackend.displayName} did not connect within ${vpnWaitTimeoutMs / 1000}s. App auto-launch skipped."
            )
            _progress.value = warning
            return warning
        }

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

        val finalState = if (launchWarning != null) {
            ActivationProgress.Warning(
                profileId = profile.id,
                message = "Profile ${profile.name} active. $launchWarning"
            )
        } else {
            ActivationProgress.Active(
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

    private fun isAlreadySatisfyingRequestedVpn(
        state: VpnConnectionState,
        profile: RegionalProfile
    ): Boolean {
        if (!state.isConnected) return false
        return when (profile.vpnBackend) {
            VpnBackend.NONE -> true
            VpnBackend.TAILSCALE -> {
                state.ownerBackend == VpnBackend.TAILSCALE ||
                    (state.ownerBackend == null && activeVpnBackend == VpnBackend.TAILSCALE)
            }
            VpnBackend.WG_TUNNEL -> {
                val sameTunnel = activeVpnBackend == VpnBackend.WG_TUNNEL &&
                    activeWgTunnelName == normalizedWgTunnel(profile)
                val ownerMatchesOrUnknown = state.ownerBackend == null ||
                    state.ownerBackend == VpnBackend.WG_TUNNEL
                sameTunnel && ownerMatchesOrUnknown
            }
        }
    }

    private fun isSwitchingWgTunnel(profile: RegionalProfile): Boolean {
        return profile.vpnBackend == VpnBackend.WG_TUNNEL &&
            activeVpnBackend == VpnBackend.WG_TUNNEL &&
            activeWgTunnelName != normalizedWgTunnel(profile)
    }

    private fun resolveConflictingBackend(
        state: VpnConnectionState,
        requested: RegionalProfile
    ): VpnBackend? {
        if (activeVpnBackend != null && activeVpnBackend != VpnBackend.NONE) {
            return activeVpnBackend
        }
        if (state.ownerBackend != null && state.ownerBackend != requested.vpnBackend) {
            return state.ownerBackend
        }
        // Unknown external VPN: disconnect the opposing supported backend as a best-effort teardown.
        return when (requested.vpnBackend) {
            VpnBackend.TAILSCALE -> VpnBackend.WG_TUNNEL
            VpnBackend.WG_TUNNEL -> VpnBackend.TAILSCALE
            VpnBackend.NONE -> null
        }
    }

    private fun isRequestedVpnReady(
        state: VpnConnectionState,
        profile: RegionalProfile,
        hadExistingVpn: Boolean,
        sawDisconnect: Boolean,
        previousHandle: Long?
    ): Boolean {
        if (!state.isConnected) return false

        // If Android explicitly reports another backend owns the active VPN, keep waiting.
        if (state.ownerBackend != null && state.ownerBackend != profile.vpnBackend) {
            return false
        }

        if (!hadExistingVpn) {
            return true
        }

        val hasNewHandle = previousHandle != null &&
            state.networkHandle != null &&
            state.networkHandle != previousHandle

        // When switching between two WG tunnels, require an observed disconnect or new handle
        // because ownerBackend remains WG_TUNNEL for both tunnels.
        if (isSwitchingWgTunnel(profile)) {
            return sawDisconnect || hasNewHandle
        }

        // If Android confirms the owner switched to the requested backend, accept immediately.
        if (state.ownerBackend == profile.vpnBackend) {
            return true
        }

        // When ownerBackend is unavailable (e.g., API 28-29 or redacted UID), require either
        // an observed disconnect transition or a new networkHandle so we never accept a stale VPN.
        return sawDisconnect || hasNewHandle
    }

    private fun normalizedWgTunnel(profile: RegionalProfile): String? {
        return if (profile.vpnBackend == VpnBackend.WG_TUNNEL) {
            profile.vpnTunnelOrNodeName?.trim()?.ifEmpty { null }
        } else {
            null
        }
    }

    /**
     * Stops mock location injection and sends VPN disconnect broadcasts.
     */
    fun deactivateAll() {
        stopMockLocation()
        vpnController.disconnectAll(
            activeWgTunnelName = activeWgTunnelName,
            wgRemoteControlKey = wgRemoteControlKeyProvider()
        )
        activeVpnBackend = null
        activeWgTunnelName = null
        _progress.value = ActivationProgress.Idle
    }

    companion object {
        const val DEFAULT_VPN_WAIT_TIMEOUT_MS: Long = 8_000L
        private const val DEFAULT_TEARDOWN_TIMEOUT_MS: Long = 3_000L
    }
}
