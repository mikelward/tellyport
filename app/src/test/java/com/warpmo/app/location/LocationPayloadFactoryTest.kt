package com.warpmo.app.location

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationPayloadFactoryTest {

    @Test
    fun createLocation_populatesFreshTimestampsProviderAndAccuracy() {
        var currentEpochMs = 1_730_000_000_000L
        var currentElapsedNanos = 5_000_000_000L

        val factory = LocationPayloadFactory(
            epochTimeProvider = { currentEpochMs },
            elapsedRealtimeNanosProvider = { currentElapsedNanos },
            random = Random(42)
        )

        val gpsFix1 = factory.createLocation(
            provider = LocationManager.GPS_PROVIDER,
            baseLatitude = 40.7128,
            baseLongitude = -74.0060,
            baseAltitude = 10.0,
            accuracyMeters = 4.5f
        )

        assertEquals(LocationManager.GPS_PROVIDER, gpsFix1.provider)
        assertEquals(1_730_000_000_000L, gpsFix1.time)
        assertEquals(5_000_000_000L, gpsFix1.elapsedRealtimeNanos)
        assertEquals(4.5f, gpsFix1.accuracy, 0.01f)
        assertTrue(gpsFix1.hasVerticalAccuracy())

        // Advance clocks by 1000ms and generate a NETWORK_PROVIDER fix.
        currentEpochMs += 1_000L
        currentElapsedNanos += 1_000_000_000L

        val netFix2 = factory.createLocation(
            provider = LocationManager.NETWORK_PROVIDER,
            baseLatitude = 40.7128,
            baseLongitude = -74.0060,
            baseAltitude = 10.0,
            accuracyMeters = 8.0f
        )

        assertEquals(LocationManager.NETWORK_PROVIDER, netFix2.provider)
        assertEquals(1_730_000_001_000L, netFix2.time)
        assertEquals(6_000_000_000L, netFix2.elapsedRealtimeNanos)
        assertEquals(8.0f, netFix2.accuracy, 0.01f)
        assertTrue(netFix2.time > gpsFix1.time)
        assertTrue(netFix2.elapsedRealtimeNanos > gpsFix1.elapsedRealtimeNanos)
    }

    @Test
    fun jitteredCoordinates_stayStrictlyUnderTwoMetersAndVaryAcrossSamples() {
        val factory = LocationPayloadFactory(
            epochTimeProvider = { 1_730_000_000_000L },
            elapsedRealtimeNanosProvider = { 1_000_000_000L },
            random = Random(12345)
        )

        val testPoints = listOf(
            40.7128 to -74.0060,   // New York
            51.5074 to -0.1278,    // London
            -33.8688 to 151.2093,  // Sydney
            64.1466 to -21.9426    // High latitude (Reykjavik)
        )

        for ((baseLat, baseLon) in testPoints) {
            val uniqueCoords = mutableSetOf<Pair<Double, Double>>()
            repeat(100) {
                val payload = factory.createPayload(
                    provider = LocationManager.GPS_PROVIDER,
                    baseLatitude = baseLat,
                    baseLongitude = baseLon,
                    baseAltitude = 15.0,
                    accuracyMeters = 5.0f
                )
                val distanceMeters = LocationPayloadFactory.haversineMeters(
                    baseLat,
                    baseLon,
                    payload.latitude,
                    payload.longitude
                )
                assertTrue(
                    "Expected jitter < 2.0m for ($baseLat, $baseLon), got ${distanceMeters}m",
                    distanceMeters < 2.0
                )
                assertTrue(
                    "Expected subtle non-zero jitter so fixes are not static, got ${distanceMeters}m",
                    distanceMeters > 0.05
                )
                uniqueCoords.add(payload.latitude to payload.longitude)
            }
            assertTrue("Expected distinct jittered fixes, got ${uniqueCoords.size}", uniqueCoords.size > 90)
        }
    }

    @Test
    fun consecutiveFixes_areNotIdentical() {
        val factory = LocationPayloadFactory(
            epochTimeProvider = { 1000L },
            elapsedRealtimeNanosProvider = { 2000L },
            random = Random(99)
        )
        val first = factory.createPayload(LocationManager.GPS_PROVIDER, 51.5074, -0.1278, 11.0, 5.0f)
        val second = factory.createPayload(LocationManager.GPS_PROVIDER, 51.5074, -0.1278, 11.0, 5.0f)

        assertNotEquals(first.latitude to first.longitude, second.latitude to second.longitude)
    }

    @Test
    fun adbMockLocationCommand_matchesExpectedAppOpsFormat() {
        assertEquals(
            "adb shell appops set com.warpmo.app android:mock_location allow",
            MockLocationPermissionChecker.buildAdbMockLocationCommand("com.warpmo.app")
        )
    }
}
