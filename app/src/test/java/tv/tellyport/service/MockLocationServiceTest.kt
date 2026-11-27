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
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.LocationManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun start_whenForegroundServiceStartDenied_reportsFailureInsteadOfCrashing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val deniedContext = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?): ComponentName? {
                throw IllegalStateException("Background foreground service start denied")
            }
        }

        MockLocationService.start(deniedContext, profile, sessionId = 79L)

        val status = MockLocationState.status.value
        assertFalse(status.isActive)
        assertEquals(79L, status.failedSessionId)
        assertNotNull(status.errorMessage)
        assertTrue(status.errorMessage!!.isNotBlank())
    }

    @Test
    fun registerTestProvider_whenProviderRejected_returnsFalseInsteadOfCrashing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val controller = Robolectric.buildService(MockLocationService::class.java).create()
        val service = controller.get()
        service.addAndEnableProvider = { _, _ ->
            throw IllegalArgumentException("GPS provider not supported on this device")
        }

        val registered = service.registerTestProvider(
            manager = manager,
            provider = LocationManager.GPS_PROVIDER,
            sessionId = 0L
        )

        assertFalse(registered)
        controller.destroy()
    }

    @Test(expected = SecurityException::class)
    fun registerTestProvider_whenMockPermissionDenied_stillThrowsSecurityException() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val controller = Robolectric.buildService(MockLocationService::class.java).create()
        val service = controller.get()
        service.addAndEnableProvider = { _, _ ->
            throw SecurityException("not allowed to perform MOCK_LOCATION")
        }

        try {
            service.registerTestProvider(
                manager = manager,
                provider = LocationManager.GPS_PROVIDER,
                sessionId = 0L
            )
        } finally {
            controller.destroy()
        }
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
