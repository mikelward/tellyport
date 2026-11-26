package tv.tellyport.boot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import tv.tellyport.data.BootRecoveryDiagnostics
import tv.tellyport.shizuku.ShizukuForceStopper

/**
 * Result of waiting for Wi-Fi or Ethernet connectivity after boot.
 */
data class NetworkReadyResult(
    val connected: Boolean,
    val transportLabel: String
)

/**
 * Injectable platform bridge for [ShizukuBootHelper] enabling deterministic JVM unit testing.
 */
interface BootRecoveryPlatform {
    suspend fun awaitWifiOrEthernet(timeoutMs: Long): NetworkReadyResult
    fun hasWriteSecureSettingsPermission(): Boolean
    fun enableWirelessDebugging(): Boolean
    fun isShizukuBinderAlive(): Boolean
    fun attemptStartShizuku(): Boolean
    fun currentTimeEpochMs(): Long
}

class AndroidBootRecoveryPlatform(
    private val context: Context
) : BootRecoveryPlatform {

    private val appContext = context.applicationContext

    override suspend fun awaitWifiOrEthernet(timeoutMs: Long): NetworkReadyResult {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkReadyResult(false, "Unavailable")

        detectCurrentWifiOrEthernet(cm)?.let { return it }

        val deferred = CompletableDeferred<NetworkReadyResult>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val label = transportLabel(caps)
                if (label != null && !deferred.isCompleted) {
                    deferred.complete(NetworkReadyResult(true, label))
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val label = transportLabel(networkCapabilities)
                if (label != null && !deferred.isCompleted) {
                    deferred.complete(NetworkReadyResult(true, label))
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        var registered = false
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
            // Re-check immediately after registration to avoid a race.
            detectCurrentWifiOrEthernet(cm)?.let { return it }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: NetworkReadyResult(false, "Timed out (${timeoutMs / 1000}s)")
        } catch (_: SecurityException) {
            return NetworkReadyResult(false, "Network state permission denied")
        } finally {
            if (registered) {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered.
                }
            }
        }
    }

    override fun hasWriteSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun enableWirelessDebugging(): Boolean {
        return try {
            Settings.Global.putInt(appContext.contentResolver, ADB_WIFI_ENABLED_KEY, 1)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun isShizukuBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    override fun attemptStartShizuku(): Boolean {
        val pm = appContext.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(ShizukuForceStopper.SHIZUKU_PACKAGE)
            ?: pm.getLaunchIntentForPackage(ShizukuForceStopper.SHIZUKU_PACKAGE)
            ?: return false

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun currentTimeEpochMs(): Long = System.currentTimeMillis()

    private fun detectCurrentWifiOrEthernet(cm: ConnectivityManager): NetworkReadyResult? {
        @Suppress("DEPRECATION")
        val networks = try {
            cm.allNetworks.toList()
        } catch (_: SecurityException) {
            listOfNotNull(cm.activeNetwork)
        }
        for (network in networks) {
            val label = transportLabel(cm.getNetworkCapabilities(network))
            if (label != null) {
                return NetworkReadyResult(true, label)
            }
        }
        return null
    }

    private fun transportLabel(caps: NetworkCapabilities?): String? {
        if (caps == null) return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> null
        }
    }

    companion object {
        const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"
    }
}

/**
 * Orchestrates post-boot recovery for Wireless Debugging (`adb_wifi_enabled`) and Shizuku
 * once Wi-Fi or Ethernet is online, persisting results to Jetpack DataStore.
 */
class ShizukuBootHelper(
    private val platform: BootRecoveryPlatform,
    private val recordDiagnostics: suspend (BootRecoveryDiagnostics) -> Unit,
    private val networkWaitTimeoutMs: Long = DEFAULT_NETWORK_WAIT_TIMEOUT_MS,
    private val shizukuRecheckDelayMs: Long = DEFAULT_SHIZUKU_RECHECK_DELAY_MS
) {
    suspend fun runBootRecovery(): BootRecoveryDiagnostics {
        val networkResult = platform.awaitWifiOrEthernet(networkWaitTimeoutMs)
        val hasWriteSecure = platform.hasWriteSecureSettingsPermission()

        val adbWifiEnabled = if (networkResult.connected && hasWriteSecure) {
            platform.enableWirelessDebugging()
        } else {
            false
        }

        var shizukuAlive = platform.isShizukuBinderAlive()
        var startAttempted = false
        if (!shizukuAlive && networkResult.connected) {
            startAttempted = platform.attemptStartShizuku()
            if (startAttempted && shizukuRecheckDelayMs > 0L) {
                delay(shizukuRecheckDelayMs)
                shizukuAlive = platform.isShizukuBinderAlive()
            }
        }

        val summary = buildString {
            if (!networkResult.connected) {
                append("Network offline after boot (${networkResult.transportLabel}).")
            } else {
                append("Network ready (${networkResult.transportLabel}). ")
                if (!hasWriteSecure) {
                    append("WRITE_SECURE_SETTINGS not granted (cannot toggle adb_wifi_enabled). ")
                } else if (adbWifiEnabled) {
                    append("Wireless Debugging enabled. ")
                } else {
                    append("Failed to enable Wireless Debugging. ")
                }
                append(
                    when {
                        shizukuAlive -> "Shizuku binder online."
                        startAttempted -> "Shizuku offline (wake attempted)."
                        else -> "Shizuku offline (app not launchable)."
                    }
                )
            }
        }.trim()

        val diagnostics = BootRecoveryDiagnostics(
            timestampEpochMs = platform.currentTimeEpochMs(),
            networkConnected = networkResult.connected,
            networkType = networkResult.transportLabel,
            writeSecureSettingsGranted = hasWriteSecure,
            adbWifiEnabled = adbWifiEnabled,
            shizukuRunning = shizukuAlive,
            shizukuStartAttempted = startAttempted,
            message = summary
        )
        recordDiagnostics(diagnostics)
        return diagnostics
    }

    companion object {
        const val DEFAULT_NETWORK_WAIT_TIMEOUT_MS: Long = 60_000L
        const val DEFAULT_SHIZUKU_RECHECK_DELAY_MS: Long = 2_000L
    }
}
