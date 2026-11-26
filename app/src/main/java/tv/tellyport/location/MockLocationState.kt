package tv.tellyport.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * In-process state holder synchronized between [tv.tellyport.service.MockLocationService]
 * and the Compose UI / coordinator layer.
 */
object MockLocationState {
    private val _status = MutableStateFlow(MockLocationStatus())
    val status: StateFlow<MockLocationStatus> = _status.asStateFlow()

    fun update(transform: (MockLocationStatus) -> MockLocationStatus) {
        _status.update(transform)
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
