package tv.tellyport.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tv.tellyport.MainActivity
import tv.tellyport.R
import tv.tellyport.data.RegionalProfile
import tv.tellyport.location.LocationPayloadFactory
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Foreground Service (`foregroundServiceType="location"`) that registers test providers
 * for both [LocationManager.GPS_PROVIDER] and [LocationManager.NETWORK_PROVIDER] and pushes
 * continuous mock location fixes every 1000ms with fresh timestamps and subtle jitter (< 2m).
 */
class MockLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val payloadFactory = LocationPayloadFactory()
    private val activeSessionId = AtomicLong(0L)
    private val providerLock = Any()

    private var injectionJob: Job? = null
    private var locationManager: LocationManager? = null
    private val registeredProviders = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSpoofingAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: "custom"
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Custom"
                val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val altitude = intent.getDoubleExtra(EXTRA_ALTITUDE, 15.0)
                val accuracy = intent.getFloatExtra(EXTRA_ACCURACY_METERS, 5.0f)

                val permissionChecker = MockLocationPermissionChecker(this)
                if (!permissionChecker.hasFineLocationPermission()) {
                    MockLocationState.markInactive(
                        errorMessage = "Fine location permission is required before starting Android 14 location service.",
                        mockPermissionDenied = !permissionChecker.isMockLocationAllowed(),
                        failedSessionId = sessionId
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Promote to foreground service immediately before doing provider work.
                try {
                    val initialNotification = buildNotification(
                        profileName = profileName,
                        latitude = latitude,
                        longitude = longitude
                    )
                    startForegroundCompat(initialNotification)
                } catch (sec: SecurityException) {
                    MockLocationState.markInactive(
                        errorMessage = "Foreground location service denied: ${sec.message}",
                        mockPermissionDenied = !permissionChecker.isMockLocationAllowed(),
                        failedSessionId = sessionId
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                startSpoofing(
                    sessionId = sessionId,
                    profileId = profileId,
                    profileName = profileName,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracyMeters = accuracy
                )
                return START_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startSpoofing(
        sessionId: Long,
        profileId: String,
        profileName: String,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracyMeters: Float
    ) {
        activeSessionId.set(sessionId)
        val previousJob = injectionJob
        val manager = locationManager
        if (manager == null) {
            previousJob?.cancel()
            injectionJob = null
            MockLocationState.markInactive(
                errorMessage = "LocationManager unavailable on this device.",
                failedSessionId = sessionId
            )
            stopSelf()
            return
        }

        injectionJob = serviceScope.launch {
            // Wait for any previous worker iteration to exit completely before replacing providers
            // or publishing the new session state so stale coordinates can never leak into this session.
            previousJob?.cancelAndJoin()
            if (!isActive || activeSessionId.get() != sessionId) return@launch

            try {
                val gpsOk: Boolean
                val networkOk: Boolean
                try {
                    gpsOk = registerTestProvider(manager, LocationManager.GPS_PROVIDER, sessionId)
                    networkOk = registerTestProvider(manager, LocationManager.NETWORK_PROVIDER, sessionId)
                } catch (sec: SecurityException) {
                    if (activeSessionId.get() == sessionId) {
                        MockLocationState.markInactive(
                            errorMessage = "Tellyport is not selected as the Mock Location app in Developer Options.",
                            mockPermissionDenied = true,
                            failedSessionId = sessionId
                        )
                        stopSpoofingAndSelf(preserveErrorState = true)
                    }
                    return@launch
                }

                if (!isActive || activeSessionId.get() != sessionId) return@launch

                if (!gpsOk && !networkOk) {
                    if (activeSessionId.get() == sessionId) {
                        MockLocationState.markInactive(
                            errorMessage = "Unable to register GPS or Network mock location providers.",
                            failedSessionId = sessionId
                        )
                        stopSpoofingAndSelf(preserveErrorState = true)
                    }
                    return@launch
                }

                MockLocationState.update {
                    MockLocationStatus(
                        isActive = true,
                        sessionId = sessionId,
                        failedSessionId = null,
                        activeProfileId = profileId,
                        activeProfileName = profileName,
                        configuredLatitude = latitude,
                        configuredLongitude = longitude,
                        configuredAltitude = altitude,
                        configuredAccuracyMeters = accuracyMeters,
                        gpsProviderRegistered = gpsOk,
                        networkProviderRegistered = networkOk,
                        injectionCount = 0L,
                        mockPermissionDenied = false,
                        errorMessage = null
                    )
                }

                var count = 0L
                while (isActive && activeSessionId.get() == sessionId) {
                    try {
                        var lastLat = latitude
                        var lastLon = longitude
                        var lastEpoch = System.currentTimeMillis()

                        if (gpsOk) {
                            if (!isActive || activeSessionId.get() != sessionId) break
                            val gpsLocation = payloadFactory.createLocation(
                                provider = LocationManager.GPS_PROVIDER,
                                baseLatitude = latitude,
                                baseLongitude = longitude,
                                baseAltitude = altitude,
                                accuracyMeters = accuracyMeters
                            )
                            synchronized(providerLock) {
                                if (activeSessionId.get() != sessionId) return@synchronized
                                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, gpsLocation)
                            }
                            lastLat = gpsLocation.latitude
                            lastLon = gpsLocation.longitude
                            lastEpoch = gpsLocation.time
                        }

                        if (networkOk) {
                            if (!isActive || activeSessionId.get() != sessionId) break
                            val networkLocation = payloadFactory.createLocation(
                                provider = LocationManager.NETWORK_PROVIDER,
                                baseLatitude = latitude,
                                baseLongitude = longitude,
                                baseAltitude = altitude,
                                accuracyMeters = maxOf(accuracyMeters, 8.0f)
                            )
                            synchronized(providerLock) {
                                if (activeSessionId.get() != sessionId) return@synchronized
                                manager.setTestProviderLocation(
                                    LocationManager.NETWORK_PROVIDER,
                                    networkLocation
                                )
                            }
                            if (!gpsOk) {
                                lastLat = networkLocation.latitude
                                lastLon = networkLocation.longitude
                                lastEpoch = networkLocation.time
                            }
                        }

                        if (!isActive || activeSessionId.get() != sessionId) break
                        count += 1
                        val recorded = MockLocationState.recordFixIfSessionMatches(
                            sessionId = sessionId,
                            latitude = lastLat,
                            longitude = lastLon,
                            epochMs = lastEpoch,
                            injectionCount = count
                        )
                        if (!recorded) break

                        if (count == 1L || count % 5L == 0L) {
                            updateNotification(profileName, lastLat, lastLon)
                        }
                    } catch (sec: SecurityException) {
                        if (activeSessionId.get() == sessionId) {
                            MockLocationState.markInactive(
                                errorMessage = "Mock location permission revoked: ${sec.message}",
                                mockPermissionDenied = true,
                                failedSessionId = sessionId
                            )
                            stopSpoofingAndSelf(preserveErrorState = true)
                        }
                        break
                    } catch (ex: Exception) {
                        if (activeSessionId.get() == sessionId) {
                            MockLocationState.update { current ->
                                if (current.sessionId != sessionId) {
                                    current
                                } else {
                                    current.copy(errorMessage = "Location push error: ${ex.message}")
                                }
                            }
                        }
                    }

                    delay(UPDATE_INTERVAL_MS)
                }
            } finally {
                if (activeSessionId.get() == 0L) {
                    removeAllTestProviders()
                }
            }
        }
    }

    private fun registerTestProvider(
        manager: LocationManager,
        provider: String,
        sessionId: Long
    ): Boolean {
        synchronized(providerLock) {
            if (activeSessionId.get() != sessionId) return false

            // Clear any stale provider left behind from an abrupt process kill.
            try {
                manager.removeTestProvider(provider)
            } catch (_: IllegalArgumentException) {
                // Provider was not registered; expected on clean startup.
            } catch (_: SecurityException) {
                // Re-thrown below during addTestProvider so caller handles mock-permission state.
            }

            if (activeSessionId.get() != sessionId) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasSatelliteRequirement(false)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                manager.addTestProvider(provider, properties)
            } else {
                addLegacyTestProvider(manager, provider)
            }

            manager.setTestProviderEnabled(provider, true)
            registeredProviders.add(provider)
            return true
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun addLegacyTestProvider(manager: LocationManager, provider: String) {
        // Pre-API 31 addTestProvider takes Criteria.POWER_LOW / Criteria.ACCURACY_FINE; API 31+
        // updated the @IntDef annotation to ProviderProperties constants with identical values.
        manager.addTestProvider(
            provider,
            false, // requiresNetwork
            false, // requiresSatellite
            false, // requiresCell
            false, // hasMonetaryCost
            true,  // supportsAltitude
            true,  // supportsSpeed
            true,  // supportsBearing
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE
        )
    }

    private fun removeAllTestProviders() {
        val manager = locationManager ?: return
        synchronized(providerLock) {
            val toRemove = registeredProviders.toSet() +
                setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            registeredProviders.clear()

            toRemove.forEach { provider ->
                try {
                    manager.setTestProviderEnabled(provider, false)
                } catch (_: Exception) {
                    // Ignore if already removed or permission not granted.
                }
                try {
                    manager.removeTestProvider(provider)
                } catch (_: Exception) {
                    // Ignore if provider was not registered as a test provider.
                }
            }
        }
    }

    private fun stopSpoofingAndSelf(preserveErrorState: Boolean = false) {
        activeSessionId.set(0L)
        injectionJob?.cancel()
        removeAllTestProviders()
        if (!preserveErrorState) {
            MockLocationState.markInactive()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        activeSessionId.set(0L)
        val jobToStop = injectionJob
        injectionJob = null
        if (jobToStop != null) {
            runBlocking {
                jobToStop.cancelAndJoin()
            }
        }
        removeAllTestProviders()
        val current = MockLocationState.status.value
        if (current.isActive) {
            MockLocationState.markInactive()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(profileName: String, latitude: Double, longitude: Double) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(profileName, latitude, longitude))
    }

    private fun buildNotification(
        profileName: String,
        latitude: Double,
        longitude: Double
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val coordsText = String.format(Locale.US, "%.4f, %.4f", latitude, longitude)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pin)
            .setContentTitle("Tellyport Active • $profileName")
            .setContentText("Spoofing $coordsText (GPS + Network)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "tv.tellyport.action.START_MOCK_LOCATION"
        const val ACTION_STOP = "tv.tellyport.action.STOP_MOCK_LOCATION"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_ACCURACY_METERS = "extra_accuracy_meters"

        const val CHANNEL_ID = "tellyport_mock_location"
        const val NOTIFICATION_ID = 4201
        const val UPDATE_INTERVAL_MS = 1_000L

        fun buildStartIntent(
            context: Context,
            profile: RegionalProfile,
            sessionId: Long = 0L
        ): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_PROFILE_ID, profile.id)
                putExtra(EXTRA_PROFILE_NAME, profile.name)
                putExtra(EXTRA_LATITUDE, profile.latitude)
                putExtra(EXTRA_LONGITUDE, profile.longitude)
                putExtra(EXTRA_ALTITUDE, profile.altitude)
                putExtra(EXTRA_ACCURACY_METERS, profile.accuracyMeters)
            }
        }

        fun buildStopIntent(context: Context): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun start(context: Context, profile: RegionalProfile, sessionId: Long = 0L) {
            ContextCompat.startForegroundService(context, buildStartIntent(context, profile, sessionId))
        }

        fun stop(context: Context) {
            // Use stopService as well as ACTION_STOP so cleanup triggers even if not currently foregrounded.
            try {
                context.startService(buildStopIntent(context))
            } catch (_: IllegalStateException) {
                context.stopService(Intent(context, MockLocationService::class.java))
            }
        }
    }
}
