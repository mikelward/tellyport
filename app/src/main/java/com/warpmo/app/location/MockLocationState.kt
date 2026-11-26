package com.warpmo.app.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live telemetry exposed by [com.warpmo.app.service.MockLocationService] to the UI.
 */
data class MockLocationStatus(
    val isActive: Boolean = false,
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
 * In-process state holder synchronized between [com.warpmo.app.service.MockLocationService]
 * and the Compose UI layer.
 */
object MockLocationState {
    private val _status = MutableStateFlow(MockLocationStatus())
    val status: StateFlow<MockLocationStatus> = _status.asStateFlow()

    fun update(transform: (MockLocationStatus) -> MockLocationStatus) {
        _status.update(transform)
    }

    fun markInactive(errorMessage: String? = null, mockPermissionDenied: Boolean = false) {
        _status.value = MockLocationStatus(
            isActive = false,
            errorMessage = errorMessage,
            mockPermissionDenied = mockPermissionDenied
        )
    }
}
