package tv.tellyport.service

import android.Manifest
import android.app.Application
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationServiceTest {

    private val profile = RegionalProfile(
        id = "uk",
        name = "London",
        regionCode = "UK",
        latitude = 51.5074,
        longitude = -0.1278,
        vpnBackend = VpnBackend.NONE
    )

    @Test
    fun start_withoutLocationPermission_reportsFailureWithoutStartingService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        MockLocationService.start(app, profile, sessionId = 77L)

        assertNull(shadowOf(app).nextStartedService)
        val status = MockLocationState.status.value
        assertFalse(status.isActive)
        assertEquals(77L, status.failedSessionId)
        assertEquals(MockLocationService.FINE_LOCATION_REQUIRED_MESSAGE, status.errorMessage)
    }

    @Test
    fun startCommand_asksSystemToRedeliverIntentAfterProcessKill() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val intent = MockLocationService.buildStartIntent(app, profile, sessionId = 78L)
        val controller = Robolectric.buildService(MockLocationService::class.java, intent).create()

        val result = controller.get().onStartCommand(intent, 0, 1)

        assertEquals(Service.START_REDELIVER_INTENT, result)
        controller.destroy()
    }
}
