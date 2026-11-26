package tv.tellyport.vpn

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.tellyport.data.ActiveVpnSession
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
import kotlin.math.abs

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
 * Coordinates VPN state reconciliation, mock location injection, persisted active-session
 * recovery, and clean streaming app relaunching without relying on redacted `ownerUid`.
 *
 * Reconciliation rules:
 * 1. Query live `ConnectivityManager` state synchronously + rehydrated DataStore [ActiveVpnSession]
 *    + `LinkProperties` Tailscale heuristic (`100.64.0.0/10` / `100.100.100.100`).
 * 2. **Already Satisfied Fast-Path:** If `TRANSPORT_VPN` is active AND (persisted
 *    `activeProfileId == targetProfile.id` OR (`targetProfile.vpnBackend == TAILSCALE` and
 *    `LinkProperties` confirms `100.64.0.0/10` is active)), skip disconnect/reconnect entirely,
 *    ensure `MockLocationService` is running for `targetProfile`, and proceed straight to launch.
 * 3. **Switching / Unattributed Conflict Path:** If `TRANSPORT_VPN` is active for a different
 *    profile or an `UNKNOWN_EXTERNAL` tunnel, broadcast disconnect to both external backends
 *    (Tailscale + WG Tunnel), wait up to 4s for `TRANSPORT_VPN` to drop (`onLost`), and clear
 *    persisted session state.
 * 4. **Connect Path:** Send target connect broadcast, wait up to 8s for `TRANSPORT_VPN` (+ 500ms
 *    stabilization delay), wait for the profile's first mock location fix, revalidate VPN state,
 *    persist [ActiveVpnSession] to DataStore, and launch the target app.
 */
class ProfileActivationCoordinator(
    private val vpnController: VpnController,
    private val vpnStateFlow: StateFlow<VpnConnectionState>,
    private val refreshLiveVpnState: () -> VpnConnectionState = { vpnStateFlow.value },
    private val mockLocationStatusFlow: StateFlow<MockLocationStatus>,
    private val startMockLocation: (RegionalProfile, Long) -> Unit,
    private val stopMockLocation: () -> Unit,
    private val wgRemoteControlKeyProvider: () -> String = { "" },
    private val persistedSessionProvider: suspend () -> ActiveVpnSession? = { null },
    private val persistActiveSession: suspend (ActiveVpnSession?) -> Unit = {},
    private val teardownWaitTimeoutMs: Long = DEFAULT_TEARDOWN_WAIT_TIMEOUT_MS,
    private val vpnWaitTimeoutMs: Long = DEFAULT_VPN_WAIT_TIMEOUT_MS,
    private val stabilizationDelayMs: Long = DEFAULT_STABILIZATION_DELAY_MS,
    private val mockLocationWaitTimeoutMs: Long = DEFAULT_MOCK_LOCATION_WAIT_TIMEOUT_MS,
    private val nextSessionId: () -> Long = MockLocationState::nextSessionId,
    private val epochTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val activationMutex = Mutex()
    private val activeCallLock = Any()

    private val _progress = MutableStateFlow<ActivationProgress>(ActivationProgress.Idle)
    val progress: StateFlow<ActivationProgress> = _progress.asStateFlow()

    @Volatile
    private var pendingWgTunnelName: String? = null

    private var activeActivationJob: Job? = null
    private var activeActivationProfileId: String? = null

    /**
     * Returns `true` if any caller (UI or AccessibilityService) is currently activating [profileId].
     */
    fun isActivatingProfile(profileId: String): Boolean = synchronized(activeCallLock) {
        activeActivationJob?.isActive == true && activeActivationProfileId == profileId
    }

    /**
     * Cancels and awaits any in-flight activation (from UI or AccessibilityService), optionally
     * scoped to [onlyProfileId]. Safe to call before acquiring [activationMutex].
     */
    suspend fun cancelActiveActivation(onlyProfileId: String? = null) {
        val jobToCancel = synchronized(activeCallLock) {
            val currentJob = activeActivationJob?.takeIf { it.isActive } ?: return@synchronized null
            if (onlyProfileId != null && activeActivationProfileId != onlyProfileId) {
                return@synchronized null
            }
            currentJob
        }
        jobToCancel?.cancelAndJoin()
    }

    /**
     * Checks whether [profile] is already satisfied right now (both VPN and mock location),
     * used by [tv.tellyport.accessibility.AppLaunchInterceptionEngine] to allow normal app
     * launches when the region is already active.
     */
    suspend fun isProfileCurrentlySatisfied(profile: RegionalProfile): Boolean {
        if (profile.vpnBackend == VpnBackend.UNCONFIGURED) return false
        val liveVpn = refreshLiveVpnState()
        val persisted = persistedSessionProvider()
        val vpnSatisfied = when (profile.vpnBackend) {
            VpnBackend.NONE -> true
            else -> isVpnAlreadySatisfied(liveVpn, persisted, profile)
        }
        if (!vpnSatisfied) return false
        return isMockLocationAlreadyRunningFor(mockLocationStatusFlow.value, profile)
    }

    /**
     * Executes the full activation / reconciliation sequence for [profile].
     *
     * @param targetPackageOverride explicit package to launch (e.g., intercepted streaming app).
     *   When `null`, falls back to [RegionalProfile.primaryTargetPackage] if `autoLaunchApp` is true.
     * @param clearTaskOnLaunch when `true`, launches with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`.
     */
    suspend fun activate(
        profile: RegionalProfile,
        targetPackageOverride: String? = null,
        clearTaskOnLaunch: Boolean = false
    ): ActivationProgress {
        val myJob = currentCoroutineContext()[Job]
        return activationMutex.withLock {
            synchronized(activeCallLock) {
                activeActivationJob = myJob
                activeActivationProfileId = profile.id
            }
            try {
                activateLocked(profile, targetPackageOverride, clearTaskOnLaunch)
            } finally {
                synchronized(activeCallLock) {
                    if (activeActivationJob === myJob) {
                        activeActivationJob = null
                        activeActivationProfileId = null
                    }
                }
            }
        }
    }

    private suspend fun activateLocked(
        profile: RegionalProfile,
        targetPackageOverride: String?,
        clearTaskOnLaunch: Boolean
    ): ActivationProgress {
        val wgKey = wgRemoteControlKeyProvider().trim()
        val targetPackage = (targetPackageOverride ?: if (profile.autoLaunchApp) profile.primaryTargetPackage else null)
            ?.trim()
            .orEmpty()

        // Guard unconfigured profiles cleanly before touching VPN or mock location.
        if (profile.vpnBackend == VpnBackend.UNCONFIGURED) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "${profile.name} has no VPN backend configured yet. Open Edit to assign Tailscale, WG Tunnel, SOCKS Wrapper, or No VPN."
            )
            _progress.value = warning
            return warning
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

        // Step 1: Synchronously query live ConnectivityManager state + rehydrated DataStore session.
        val liveVpn = refreshLiveVpnState()
        val rawPersisted = persistedSessionProvider()
        val persistedSession = if (isPersistedSessionValidForLiveVpn(liveVpn, rawPersisted)) {
            rawPersisted
        } else {
            // Clear stale persisted session if VPN dropped, switched backend, or changed network handle.
            if (rawPersisted != null) {
                persistActiveSession(null)
            }
            null
        }

        // Fast path for profiles with VpnBackend.NONE (mock location only).
        if (profile.vpnBackend == VpnBackend.NONE) {
            val mockResult = ensureMockLocationRunning(profile)
            return finishWithOptionalLaunch(
                profile = profile,
                targetPackage = targetPackage,
                clearTaskOnLaunch = clearTaskOnLaunch,
                vpnReady = true,
                mockResult = mockResult
            )
        }

        // Step 2: Already Satisfied Fast-Path (verified persisted profile match OR Tailscale 100.64.0.0/10 match).
        if (isVpnAlreadySatisfied(liveVpn, persistedSession, profile)) {
            val mockResult = ensureMockLocationRunning(profile)
            val revalidated = refreshLiveVpnState()
            val vpnStillReady = revalidated.isConnected && (
                (profile.vpnBackend == VpnBackend.TAILSCALE &&
                    revalidated.inferredBackend == InferredVpnBackend.TAILSCALE) ||
                    isPersistedSessionValidForLiveVpn(revalidated, persistedSession)
                )

            if (vpnStillReady) {
                persistActiveSession(
                    ActiveVpnSession(
                        profileId = profile.id,
                        vpnBackend = profile.vpnBackend,
                        tunnelName = normalizedTunnel(profile),
                        networkHandle = revalidated.networkHandle,
                        activatedAtEpochMs = epochTimeProvider()
                    )
                )
            } else {
                persistActiveSession(null)
            }

            return finishWithOptionalLaunch(
                profile = profile,
                targetPackage = targetPackage,
                clearTaskOnLaunch = clearTaskOnLaunch,
                vpnReady = vpnStillReady,
                mockResult = mockResult
            )
        }

        // Start/update MockLocationService early so provider setup runs in parallel with VPN switching.
        val sessionId = nextSessionId()
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Starting mock location for ${profile.name}..."
        )
        startMockLocation(profile, sessionId)

        val previousHandle = liveVpn.networkHandle
        val hadConflictingVpn = liveVpn.isConnected
        var sawDisconnect = !hadConflictingVpn

        // Step 3: Switching / Unattributed Conflict Path.
        if (hadConflictingVpn) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Disconnecting previous VPN before connecting ${profile.vpnBackend.displayName}..."
            )
            val wgTunnelsToStop = buildList<String?> {
                add(null) // primary/default WG tunnel
                persistedSession?.takeIf { it.vpnBackend == VpnBackend.WG_TUNNEL }?.tunnelName?.let { add(it) }
                pendingWgTunnelName?.let { add(it) }
            }
            vpnController.disconnectAll(
                wgTunnelNames = wgTunnelsToStop,
                wgRemoteControlKey = wgKey,
                includeSocksWrapper = persistedSession?.vpnBackend == VpnBackend.SOCKS_WRAPPER
            )
            pendingWgTunnelName = null
            persistActiveSession(null)

            val dropped = withTimeoutOrNull(teardownWaitTimeoutMs) {
                vpnStateFlow.first { state -> !state.isConnected }
            }
            if (dropped != null) {
                sawDisconnect = true
            }
        } else {
            // Stop any lingering pending WG tunnel that differs from this profile.
            val stalePendingWg = pendingWgTunnelName
            if (stalePendingWg != null &&
                (profile.vpnBackend != VpnBackend.WG_TUNNEL || stalePendingWg != normalizedTunnel(profile))
            ) {
                vpnController.disconnect(
                    backend = VpnBackend.WG_TUNNEL,
                    tunnelName = stalePendingWg,
                    wgRemoteControlKey = wgKey
                )
                pendingWgTunnelName = null
            }
        }

        // Step 4: Connect Path — record pending WG tunnel BEFORE sending START_TUNNEL.
        if (profile.vpnBackend == VpnBackend.WG_TUNNEL) {
            pendingWgTunnelName = normalizedTunnel(profile)
        }

        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Connecting ${profile.vpnBackend.displayName} (${profile.name})..."
        )
        vpnController.connect(profile, wgKey)

        val connectedState = withTimeoutOrNull(vpnWaitTimeoutMs) {
            vpnStateFlow.first { state ->
                if (!state.isConnected) {
                    sawDisconnect = true
                    return@first false
                }
                if (!isLiveVpnCompatibleWithProfile(state, profile)) {
                    return@first false
                }
                if (!hadConflictingVpn) {
                    true
                } else {
                    val hasNewHandle = previousHandle != null &&
                        state.networkHandle != null &&
                        state.networkHandle != previousHandle
                    sawDisconnect || hasNewHandle
                }
            }
        }

        if (connectedState != null && stabilizationDelayMs > 0L) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Stabilizing ${profile.vpnBackend.displayName} tunnel..."
            )
            delay(stabilizationDelayMs)
        }

        val mockResult = waitForMockLocationFix(profile, sessionId)

        // Revalidate live VPN handle & backend compatibility after stabilization & mock-location wait
        // so a replacement VPN network during the wait is never persisted or launched through.
        val finalVpnState = refreshLiveVpnState()
        val vpnReady = revalidateConnectedVpn(
            connectedState = connectedState,
            finalVpnState = finalVpnState,
            profile = profile
        )

        if (vpnReady) {
            pendingWgTunnelName = null
            persistActiveSession(
                ActiveVpnSession(
                    profileId = profile.id,
                    vpnBackend = profile.vpnBackend,
                    tunnelName = normalizedTunnel(profile),
                    networkHandle = finalVpnState.networkHandle,
                    activatedAtEpochMs = epochTimeProvider()
                )
            )
        } else {
            persistActiveSession(null)
        }

        return finishWithOptionalLaunch(
            profile = profile,
            targetPackage = targetPackage,
            clearTaskOnLaunch = clearTaskOnLaunch,
            vpnReady = vpnReady,
            mockResult = mockResult
        )
    }

    private fun revalidateConnectedVpn(
        connectedState: VpnConnectionState?,
        finalVpnState: VpnConnectionState,
        profile: RegionalProfile
    ): Boolean {
        if (connectedState == null || !finalVpnState.isConnected) return false
        if (!isLiveVpnCompatibleWithProfile(finalVpnState, profile)) return false
        val expectedHandle = connectedState.networkHandle
        val finalHandle = finalVpnState.networkHandle
        if (expectedHandle != null && finalHandle != null && finalHandle != expectedHandle) {
            return false
        }
        return true
    }

    private fun isLiveVpnCompatibleWithProfile(
        liveVpn: VpnConnectionState,
        profile: RegionalProfile
    ): Boolean {
        if (!liveVpn.isConnected) return false
        if (liveVpn.inferredBackend == InferredVpnBackend.TAILSCALE &&
            profile.vpnBackend != VpnBackend.TAILSCALE
        ) {
            return false
        }
        if (profile.vpnBackend == VpnBackend.TAILSCALE &&
            liveVpn.inferredBackend == InferredVpnBackend.UNKNOWN_EXTERNAL &&
            liveVpn.ipv4Addresses.isNotEmpty()
        ) {
            return false
        }
        return true
    }

    /**
     * Aborts any in-flight activation (regardless of whether started by the UI or
     * AccessibilityService), then stops mock location injection, clears persisted [ActiveVpnSession],
     * and broadcasts disconnect to Tailscale plus any persisted/pending WG tunnels.
     */
    suspend fun deactivateAll() {
        cancelActiveActivation()
        activationMutex.withLock {
            stopMockLocation()
            val persisted = persistedSessionProvider()
            val wgTunnelsToStop = buildList<String?> {
                persisted?.takeIf { it.vpnBackend == VpnBackend.WG_TUNNEL }?.tunnelName?.let { add(it) }
                pendingWgTunnelName?.let { add(it) }
            }
            vpnController.disconnectAll(
                wgTunnelNames = wgTunnelsToStop,
                wgRemoteControlKey = wgRemoteControlKeyProvider(),
                includeSocksWrapper = persisted?.vpnBackend == VpnBackend.SOCKS_WRAPPER
            )
            pendingWgTunnelName = null
            persistActiveSession(null)
            _progress.value = ActivationProgress.Idle
        }
    }

    /**
     * Clears persisted session state when [VpnNetworkMonitor] observes `TRANSPORT_VPN` lost.
     */
    suspend fun onVpnTransportLost() {
        persistActiveSession(null)
    }

    private fun isVpnAlreadySatisfied(
        liveVpn: VpnConnectionState,
        persisted: ActiveVpnSession?,
        targetProfile: RegionalProfile
    ): Boolean {
        if (!liveVpn.isConnected) return false

        // 1. Persisted DataStore session matches this exact profile, backend, and tunnel,
        //    AND is still verified against the live VPN's inferred backend & networkHandle.
        if (persisted != null &&
            isPersistedSessionValidForLiveVpn(liveVpn, persisted) &&
            persisted.profileId == targetProfile.id &&
            persisted.vpnBackend == targetProfile.vpnBackend &&
            persisted.tunnelName == normalizedTunnel(targetProfile)
        ) {
            return true
        }

        // 2. Cold-start / external Tailscale recovery: LinkProperties confirms 100.64.0.0/10
        //    (or 100.100.100.100 MagicDNS) is active and the target profile uses TAILSCALE.
        if (targetProfile.vpnBackend == VpnBackend.TAILSCALE &&
            liveVpn.inferredBackend == InferredVpnBackend.TAILSCALE
        ) {
            return true
        }

        return false
    }

    private fun isMockLocationAlreadyRunningFor(
        status: MockLocationStatus,
        profile: RegionalProfile
    ): Boolean {
        if (!status.isActive || status.activeProfileId != profile.id || status.injectionCount <= 0L) {
            return false
        }
        val latMatch = status.configuredLatitude?.let { abs(it - profile.latitude) < 1e-5 } == true
        val lonMatch = status.configuredLongitude?.let { abs(it - profile.longitude) < 1e-5 } == true
        val altMatch = status.configuredAltitude?.let { abs(it - profile.altitude) < 1e-3 } == true
        val accMatch = status.configuredAccuracyMeters?.let { abs(it - profile.accuracyMeters) < 1e-3f } == true
        return latMatch && lonMatch && altMatch && accMatch
    }

    private suspend fun ensureMockLocationRunning(profile: RegionalProfile): MockWaitResult {
        if (isMockLocationAlreadyRunningFor(mockLocationStatusFlow.value, profile)) {
            return MockWaitResult.Ready
        }
        val sessionId = nextSessionId()
        _progress.value = ActivationProgress.InProgress(
            profileId = profile.id,
            message = "Starting mock location for ${profile.name}..."
        )
        startMockLocation(profile, sessionId)
        return waitForMockLocationFix(profile, sessionId)
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
        targetPackage: String,
        clearTaskOnLaunch: Boolean,
        vpnReady: Boolean,
        mockResult: MockWaitResult
    ): ActivationProgress {
        if (!vpnReady && mockResult is MockWaitResult.Failed) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "${profile.vpnBackend.displayName} did not connect and ${mockResult.reason} App launch skipped."
            )
            _progress.value = warning
            return warning
        }

        if (!vpnReady) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "Mock location active for ${profile.name}, but ${profile.vpnBackend.displayName} did not connect within ${vpnWaitTimeoutMs / 1000}s. App launch skipped."
            )
            _progress.value = warning
            return warning
        }

        if (mockResult is MockWaitResult.Failed) {
            val warning = ActivationProgress.Warning(
                profileId = profile.id,
                message = "${mockResult.reason} App launch skipped."
            )
            _progress.value = warning
            return warning
        }

        var launchWarning: String? = null
        if (targetPackage.isNotEmpty()) {
            _progress.value = ActivationProgress.InProgress(
                profileId = profile.id,
                message = "Launching $targetPackage..."
            )
            val launched = vpnController.launchApp(targetPackage, clearTask = clearTaskOnLaunch)
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
                    if (targetPackage.isNotEmpty()) {
                        append(" • Launched $targetPackage")
                    }
                }
            )
        }

        _progress.value = finalState
        return finalState
    }

    private fun normalizedTunnel(profile: RegionalProfile): String? {
        return profile.tunnelName?.trim()?.ifEmpty { null }
    }

    companion object {
        const val DEFAULT_TEARDOWN_WAIT_TIMEOUT_MS: Long = 4_000L
        const val DEFAULT_VPN_WAIT_TIMEOUT_MS: Long = 8_000L
        const val DEFAULT_STABILIZATION_DELAY_MS: Long = 500L
        const val DEFAULT_MOCK_LOCATION_WAIT_TIMEOUT_MS: Long = 5_000L

        /**
         * Verifies that a persisted [ActiveVpnSession] still matches the currently active
         * `TRANSPORT_VPN` network:
         * - Rejects when disconnected.
         * - Rejects explicit backend mismatches (e.g., live Tailscale `100.64.0.0/10` vs persisted WG,
         *   or persisted Tailscale when live VPN has non-Tailscale IPv4 addresses).
         * - Rejects when the live `networkHandle` differs from the persisted `networkHandle`, or
         *   when a non-Tailscale persisted session has no verifiable `networkHandle`.
         */
        fun isPersistedSessionValidForLiveVpn(
            liveVpn: VpnConnectionState,
            persisted: ActiveVpnSession?
        ): Boolean {
            if (persisted == null || !liveVpn.isConnected) return false

            // Live LinkProperties explicitly identifies Tailscale -> reject non-Tailscale sessions.
            if (liveVpn.inferredBackend == InferredVpnBackend.TAILSCALE &&
                persisted.vpnBackend != VpnBackend.TAILSCALE
            ) {
                return false
            }

            // Persisted Tailscale session while live VPN is non-Tailscale with observed IPv4 addresses.
            if (persisted.vpnBackend == VpnBackend.TAILSCALE &&
                liveVpn.inferredBackend == InferredVpnBackend.UNKNOWN_EXTERNAL &&
                liveVpn.ipv4Addresses.isNotEmpty()
            ) {
                return false
            }

            val persistedHandle = persisted.networkHandle
            val liveHandle = liveVpn.networkHandle
            if (persistedHandle != null && liveHandle != null && persistedHandle != liveHandle) {
                return false
            }

            // Require a verifiable networkHandle for non-Tailscale backends so an unattributed
            // external VPN cannot falsely satisfy a persisted WG/SOCKS session without handle proof.
            if (persisted.vpnBackend != VpnBackend.TAILSCALE &&
                (persistedHandle == null || liveHandle == null)
            ) {
                return false
            }

            return true
        }
    }
}
