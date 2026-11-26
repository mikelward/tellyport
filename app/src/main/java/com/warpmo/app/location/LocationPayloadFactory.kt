package com.warpmo.app.location

import android.location.Location
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure value representation of a single mock location fix prior to Android [Location] wrapping.
 */
data class MockLocationPayload(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    val verticalAccuracyMeters: Float,
    val speedMetersPerSec: Float,
    val bearingDegrees: Float,
    val timeEpochMs: Long,
    val elapsedRealtimeNanos: Long
)

/**
 * Constructs realistic mock [Location] objects with fresh wall-clock and monotonic timestamps
 * plus subtle sub-2-meter coordinate jitter so FusedLocationProviderClient and streaming apps
 * accept continuous updates without rejecting static or stale fixes.
 */
class LocationPayloadFactory(
    private val epochTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeNanosProvider: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val random: Random = Random.Default,
    private val maxJitterMeters: Double = DEFAULT_MAX_JITTER_METERS
) {
    init {
        require(maxJitterMeters in 0.0..1.99) {
            "maxJitterMeters must be in [0.0, 1.99] to stay strictly under 2 meters"
        }
    }

    /**
     * Generates a [MockLocationPayload] for [provider] near ([baseLatitude], [baseLongitude])
     * with fresh timestamps and realistic jitter (< 2 meters).
     */
    fun createPayload(
        provider: String,
        baseLatitude: Double,
        baseLongitude: Double,
        baseAltitude: Double,
        accuracyMeters: Float
    ): MockLocationPayload {
        val (jitteredLat, jitteredLon) = applySubTwoMeterJitter(baseLatitude, baseLongitude)
        val altitudeJitter = if (maxJitterMeters > 0.0) {
            (random.nextDouble() - 0.5) * 0.6 // ±0.3m vertical variation
        } else {
            0.0
        }
        val safeAccuracy = max(accuracyMeters, 1.0f)

        return MockLocationPayload(
            provider = provider,
            latitude = jitteredLat,
            longitude = jitteredLon,
            altitude = baseAltitude + altitudeJitter,
            accuracyMeters = safeAccuracy,
            verticalAccuracyMeters = max(safeAccuracy * 0.6f, 1.5f),
            speedMetersPerSec = 0.0f,
            bearingDegrees = 0.0f,
            timeEpochMs = epochTimeProvider(),
            elapsedRealtimeNanos = elapsedRealtimeNanosProvider()
        )
    }

    /**
     * Builds an Android [Location] populated with fresh timestamps, accuracy, and subtle jitter.
     */
    fun createLocation(
        provider: String,
        baseLatitude: Double,
        baseLongitude: Double,
        baseAltitude: Double,
        accuracyMeters: Float
    ): Location {
        val payload = createPayload(
            provider = provider,
            baseLatitude = baseLatitude,
            baseLongitude = baseLongitude,
            baseAltitude = baseAltitude,
            accuracyMeters = accuracyMeters
        )
        return toAndroidLocation(payload)
    }

    /**
     * Converts a [MockLocationPayload] into an Android [Location] instance.
     */
    fun toAndroidLocation(payload: MockLocationPayload): Location {
        return Location(payload.provider).apply {
            latitude = payload.latitude
            longitude = payload.longitude
            altitude = payload.altitude
            accuracy = payload.accuracyMeters
            speed = payload.speedMetersPerSec
            bearing = payload.bearingDegrees
            time = payload.timeEpochMs
            elapsedRealtimeNanos = payload.elapsedRealtimeNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = payload.verticalAccuracyMeters
                speedAccuracyMetersPerSecond = 0.1f
                bearingAccuracyDegrees = 1.0f
            }
        }
    }

    private fun applySubTwoMeterJitter(baseLat: Double, baseLon: Double): Pair<Double, Double> {
        if (maxJitterMeters <= 0.0) {
            return baseLat.coerceIn(-90.0, 90.0) to normalizeLongitude(baseLon)
        }
        // Keep a tiny minimum offset (0.15m) so consecutive updates are never bit-identical
        val minRadius = minOf(0.15, maxJitterMeters * 0.25)
        val radiusMeters = minRadius + (maxJitterMeters - minRadius) * sqrt(random.nextDouble())
        val angleRad = random.nextDouble() * 2.0 * PI

        val northMeters = radiusMeters * cos(angleRad)
        val eastMeters = radiusMeters * sin(angleRad)

        val latDelta = northMeters / METERS_PER_DEGREE_LAT
        val cosLat = max(cos(Math.toRadians(baseLat.coerceIn(-89.9, 89.9))), 0.01)
        val lonDelta = eastMeters / (METERS_PER_DEGREE_LAT * cosLat)

        val lat = (baseLat + latDelta).coerceIn(-90.0, 90.0)
        val lon = normalizeLongitude(baseLon + lonDelta)
        return lat to lon
    }

    private fun normalizeLongitude(lon: Double): Double {
        var result = lon
        while (result > 180.0) result -= 360.0
        while (result < -180.0) result += 360.0
        return result
    }

    companion object {
        const val DEFAULT_MAX_JITTER_METERS: Double = 1.6
        private const val METERS_PER_DEGREE_LAT: Double = 111_320.0
        private const val EARTH_RADIUS_METERS: Double = 6_371_000.0

        /**
         * Calculates great-circle Haversine distance in meters between two WGS84 coordinates.
         */
        fun haversineMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)

            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }
    }
}
