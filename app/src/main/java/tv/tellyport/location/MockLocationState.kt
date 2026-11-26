package tv.tellyport.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Live telemetry exposed by [tv.tellyport.service.MockLocationService] to the UI
 * and activation coordinator.
 */
data class MockLocationStatus(
    val isActive: Boolean = false,
    val sessionId: Long = 0L,
    val failedSessionId: Long? = null,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val configuredLatitude: Double? = null,
    val configuredLongitude: Double? = null,
    val configuredAltitude: Double? = null,
    val lastInjectedLatitude: Double? = null,
    val lastInjectedLongitude: Double? = null,
    val lastUpdatedEpochMs: Long? = null,
    val gpsProviderRegistered: Boolean = false,
    val networkProviderRegistered: Boolean = false,
    val injectionCount: Long = 0L,
    val mockPermissionDenied: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Process-scoped state holder synchronized between [tv.tellyport.service.MockLocationService]
 * and the Compose UI / coordinator layer.
 */
object MockLocationState {
    private val sessionCounter = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L) * 1_000L)

    private val _status = MutableStateFlow(MockLocationStatus())
    val status: StateFlow<MockLocationStatus> = _status.asStateFlow()

    /**
     * Generates a monotonically increasing, process-scoped session ID that cannot collide across
     * ViewModel / coordinator recreations while the foreground service stays alive.
     */
    fun nextSessionId(): Long = sessionCounter.incrementAndGet()

    fun update(transform: (MockLocationStatus) -> MockLocationStatus) {
        _status.update(transform)
    }

    /**
     * Atomically records an injected fix ONLY if [sessionId] still matches the currently active
     * session, preventing a cancelling/stale worker from publishing old coordinates into a newer session.
     */
    fun recordFixIfSessionMatches(
        sessionId: Long,
        latitude: Double,
        longitude: Double,
        epochMs: Long,
        injectionCount: Long
    ): Boolean {
        var applied = false
        _status.update { current ->
            if (!current.isActive || current.sessionId != sessionId) {
                applied = false
                current
            } else {
                applied = true
                current.copy(
                    isActive = true,
                    lastInjectedLatitude = latitude,
                    lastInjectedLongitude = longitude,
                    lastUpdatedEpochMs = epochMs,
                    injectionCount = injectionCount,
                    mockPermissionDenied = false,
                    errorMessage = null
                )
            }
        }
        return applied
    }

    fun markInactive(
        errorMessage: String? = null,
        mockPermissionDenied: Boolean = false,
        failedSessionId: Long? = null
    ) {
        _status.value = MockLocationStatus(
            isActive = false,
            failedSessionId = failedSessionId,
            errorMessage = errorMessage,
            mockPermissionDenied = mockPermissionDenied
        )
    }
}
