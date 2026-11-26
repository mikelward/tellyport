package tv.tellyport.vpn

import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
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
 * 1. Coordinate teardown/reconnection of any conflicting or unidentified existing VPN session,
 *    then send the VPN connect broadcast (if configured).
 * 2. Start [tv.tellyport.service.MockLocationService] with a unique session ID and the
 *    profile's coordinates.
 * 3. Wait up to 8 seconds for the requested VPN to become ready (or skip if `NONE`) AND wait
 *    for the requested profile's first mock location fix to be injected.
 * 4. Launch `targetAppPackage` only if `autoLaunchApp` is true and both VPN + mock location are ready.
 */
class ProfileActivationCoordinator(
    private val vpnController: VpnController,
    private val vpnStateFlow: StateFlow<VpnConnectionState>,
    private val mockLocationStatusFlow: StateFlow<MockLocationStatus>,
    private val startMockLocation: (RegionalProfile, Long) -> Unit,
    private val stopMockLocation: () -> Unit,
    private val wgRemoteControlKeyProvider: () -> String = { "" },
    private val vpnWaitTimeoutMs: Long = DEFAULT_VPN_WAIT_TIMEOUT_MS,
    private val mockLocationWaitTimeoutMs: Long = DEFAULT_MOCK_LOCATION_WAIT_TIMEOUT_MS,
    private val nextSessionId: () -> Long = MockLocationState::nextSessionId
) {
    private val _progress = MutableStateFlow<ActivationProgress>(ActivationProgress.Idle)
    val progress: StateFlow<ActivationProgress> = _progress.asStateFlow()

    private var activeVpnBackend: VpnBackend? = null
    private var activeWgTunnelName: String? = null
    private var pendingWgTunnelName: String? = null

    suspend fun activate(profile: RegionalProfile): ActivationProgress {
        val wgKey = wgRemoteControlKeyProvider().trim()

        // If the underlying VPN transport dropped since our last activation, clear confirmed backend.
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

        val sessionId = nextSessionId()

        // Fast path for profiles that do not use a VPN backend: still wait for the first mock fix.
        if (profile.vpnBackend == VpnBackend.NONE) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Starting mock location for ${profile.name}..."
            )
            startMockLocation(profile, sessionId)
            val mockResult = waitForMockLocationFix(profile, sessionId)
            return finishWithOptionalLaunch(profile, vpnReady = true, mockResult = mockResult)
        }

        val initialVpnState = vpnStateFlow.value
        val alreadySatisfied = isAlreadySatisfyingRequestedVpn(initialVpnState, profile)

        // Start mock location early so provider registration & fix #1 run in parallel with VPN setup.
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Starting mock location for ${profile.name}..."
        )
        startMockLocation(profile, sessionId)

        if (alreadySatisfied) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Verifying ${profile.vpnBackend.displayName}..."
            )
            if (profile.vpnBackend == VpnBackend.WG_TUNNEL) {
                pendingWgTunnelName = normalizedWgTunnel(profile)
            }
            vpnController.connect(profile, wgKey)

            val mockResult = waitForMockLocationFix(profile, sessionId)
            val vpnStillReady = revalidateVpnStillReady(
                expectedConnectedState = initialVpnState,
                profile = profile
            )
            if (vpnStillReady) {
                activeVpnBackend = profile.vpnBackend
                activeWgTunnelName = normalizedWgTunnel(profile)
                pendingWgTunnelName = null
            } else {
                activeVpnBackend = null
                activeWgTunnelName = null
            }
            return finishWithOptionalLaunch(profile, vpnReady = vpnStillReady, mockResult = mockResult)
        }

        val previousHandle = initialVpnState.networkHandle
        val hadExistingVpn = initialVpnState.isConnected
        var sawDisconnect = !hadExistingVpn

        // If a prior WG tunnel start was left pending (e.g., from an earlier timeout) and differs
        // from this profile, stop it cleanly before starting the new backend.
        val stalePendingWg = pendingWgTunnelName
        if (stalePendingWg != null &&
            (profile.vpnBackend != VpnBackend.WG_TUNNEL || stalePendingWg != normalizedWgTunnel(profile))
        ) {
            vpnController.disconnect(
                backend = VpnBackend.WG_TUNNEL,
                tunnelOrNodeName = stalePendingWg,
                wgRemoteControlKey = wgKey
            )
            pendingWgTunnelName = null
        }

        if (hadExistingVpn) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Reconnecting VPN for ${profile.vpnBackend.displayName}..."
            )
            dispatchPreConnectTeardown(initialVpnState, profile, wgKey)
        }

        // Total VPN teardown + connect wait is bounded by vpnWaitTimeoutMs.
        val connectedState = withTimeoutOrNull(vpnWaitTimeoutMs) {
            if (hadExistingVpn) {
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

            // Record pending WG tunnel BEFORE sending START_TUNNEL so cancellation/Delete/Disconnect All
            // during the readiness wait can stop the exact named tunnel.
            if (profile.vpnBackend == VpnBackend.WG_TUNNEL) {
                pendingWgTunnelName = normalizedWgTunnel(profile)
            }

            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Waiting up to ${vpnWaitTimeoutMs / 1000}s for ${profile.vpnBackend.displayName}..."
            )
            vpnController.connect(profile, wgKey)

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

        val mockResult = waitForMockLocationFix(profile, sessionId)

        // Revalidate that the requested VPN is still active after the mock-location wait completes
        // so we never launch a streaming app or report Active if the VPN dropped in the meantime.
        val vpnReady = connectedState != null && revalidateVpnStillReady(
            expectedConnectedState = connectedState,
            profile = profile
        )
        if (vpnReady) {
            activeVpnBackend = profile.vpnBackend
            activeWgTunnelName = normalizedWgTunnel(profile)
            pendingWgTunnelName = null
        } else {
            activeVpnBackend = null
            activeWgTunnelName = null
        }

        return finishWithOptionalLaunch(profile, vpnReady = vpnReady, mockResult = mockResult)
    }

    private fun revalidateVpnStillReady(
        expectedConnectedState: VpnConnectionState,
        profile: RegionalProfile
    ): Boolean {
        val latest = vpnStateFlow.value
        if (!latest.isConnected) return false
        if (latest.ownerBackend != null && latest.ownerBackend != profile.vpnBackend) return false
        val expectedHandle = expectedConnectedState.networkHandle
        if (expectedHandle != null &&
            latest.networkHandle != null &&
            latest.networkHandle != expectedHandle &&
            latest.ownerBackend != profile.vpnBackend
        ) {
            return false
        }
        return true
    }

    /**
     * Dispatches teardown for an existing VPN session before connecting [requested].
     * When the existing session's owner is unknown (e.g., Tellyport launched with Tailscale or WG
     * already connected and Android redacts `ownerUid`), explicitly disconnects supported backends
     * so the requested backend performs a deterministic teardown + reconnect.
     */
    private fun dispatchPreConnectTeardown(
        initialVpnState: VpnConnectionState,
        requested: RegionalProfile,
        wgKey: String
    ) {
        val knownActive = activeVpnBackend?.takeIf { it != VpnBackend.NONE }
        if (knownActive != null) {
            val tunnelToDisconnect = if (knownActive == VpnBackend.WG_TUNNEL) {
                activeWgTunnelName
            } else {
                null
            }
            vpnController.disconnect(
                backend = knownActive,
                tunnelOrNodeName = tunnelToDisconnect,
                wgRemoteControlKey = wgKey
            )
            return
        }

        val detectedOwner = initialVpnState.ownerBackend?.takeIf { it != VpnBackend.NONE }
        if (detectedOwner != null) {
            val tunnelToDisconnect = if (detectedOwner == VpnBackend.WG_TUNNEL) {
                normalizedWgTunnel(requested)
            } else {
                null
            }
            vpnController.disconnect(
                backend = detectedOwner,
                tunnelOrNodeName = tunnelToDisconnect,
                wgRemoteControlKey = wgKey
            )
            return
        }

        // Unknown external VPN owner (ownerUid redacted): coordinate explicit teardown across
        // supported backends so even an already-connected Tailscale/WG session cleanly reconnects.
        val wgTunnels = buildList<String?> {
            add(null) // primary/default WG tunnel
            normalizedWgTunnel(requested)?.let { add(it) }
            pendingWgTunnelName?.let { add(it) }
        }
        vpnController.disconnectAll(
            wgTunnelNames = wgTunnels,
            wgRemoteControlKey = wgKey
        )
    }

    private sealed class MockWaitResult {
        data object Ready : MockWaitResult()
        data class Failed(val reason: String) : MockWaitResult()
    }

    private suspend fun waitForMockLocationFix(
        profile: RegionalProfile,
        sessionId: Long
    ): MockWaitResult {
        val current = mockLocationStatusFlow.value
        if (isMockFixReady(current, profile.id, sessionId)) {
            return MockWaitResult.Ready
        }
        if (isMockStartupFailed(current, sessionId)) {
            return MockWaitResult.Failed(
                current.errorMessage ?: "Mock location service failed to start for ${profile.name}."
            )
        }

        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Waiting for mock location fix (${profile.name})..."
        )

        val observed = withTimeoutOrNull(mockLocationWaitTimeoutMs) {
            mockLocationStatusFlow.first { status ->
                isMockFixReady(status, profile.id, sessionId) ||
                    isMockStartupFailed(status, sessionId)
            }
        }

        return when {
            observed == null -> MockWaitResult.Failed(
                "Mock location service did not inject a fix for ${profile.name} within ${mockLocationWaitTimeoutMs / 1000}s."
            )
            isMockFixReady(observed, profile.id, sessionId) -> MockWaitResult.Ready
            else -> MockWaitResult.Failed(
                observed.errorMessage ?: "Mock location service failed to start for ${profile.name}."
            )
        }
    }

    private fun isMockFixReady(
        status: MockLocationStatus,
        profileId: String,
        sessionId: Long
    ): Boolean {
        return status.isActive &&
            status.activeProfileId == profileId &&
            status.sessionId == sessionId &&
            status.injectionCount > 0L
    }

    private fun isMockStartupFailed(
        status: MockLocationStatus,
        sessionId: Long
    ): Boolean {
        return !status.isActive &&
            status.failedSessionId == sessionId &&
            (status.mockPermissionDenied || !status.errorMessage.isNullOrBlank())
    }

    private fun finishWithOptionalLaunch(
        profile: RegionalProfile,
        vpnReady: Boolean,
        mockResult: MockWaitResult
    ): ActivationProgress {
        val targetPackage = profile.targetAppPackage?.trim().orEmpty()

        // Do not launch the streaming app if either the requested VPN or mock location failed.
        if (!vpnReady && mockResult is MockWaitResult.Failed) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "${profile.vpnBackend.displayName} did not connect and ${mockResult.reason} App auto-launch skipped."
            )
            _progress.value = warning
            return warning
        }

        if (!vpnReady) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "Mock location active for ${profile.name}, but ${profile.vpnBackend.displayName} did not connect within ${vpnWaitTimeoutMs / 1000}s. App auto-launch skipped."
            )
            _progress.value = warning
            return warning
        }

        if (mockResult is MockWaitResult.Failed) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "${mockResult.reason} App auto-launch skipped."
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

        // When ownerBackend is unavailable (redacted UID or API 28-29), require either an
        // observed disconnect transition or a new networkHandle so we never accept a stale VPN.
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
     * Stops mock location injection and sends VPN disconnect broadcasts for Tailscale
     * plus any confirmed active OR pending named WG tunnels.
     */
    fun deactivateAll() {
        stopMockLocation()
        val wgTunnelsToStop = buildList<String?> {
            activeWgTunnelName?.let { add(it) }
            pendingWgTunnelName?.let { add(it) }
        }
        vpnController.disconnectAll(
            wgTunnelNames = wgTunnelsToStop,
            wgRemoteControlKey = wgRemoteControlKeyProvider()
        )
        activeVpnBackend = null
        activeWgTunnelName = null
        pendingWgTunnelName = null
        _progress.value = ActivationProgress.Idle
    }

    companion object {
        const val DEFAULT_VPN_WAIT_TIMEOUT_MS: Long = 8_000L
        const val DEFAULT_MOCK_LOCATION_WAIT_TIMEOUT_MS: Long = 5_000L
        private const val DEFAULT_TEARDOWN_TIMEOUT_MS: Long = 3_000L
    }
}
