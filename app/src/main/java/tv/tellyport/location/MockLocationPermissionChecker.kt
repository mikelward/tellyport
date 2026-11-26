package tv.tellyport.location

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Snapshot of location & mock-location prerequisites required on Android 14 / Google TV.
 */
data class MockLocationPermissionStatus(
    val isMockLocationAppSelected: Boolean,
    val hasFineLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val adbCommand: String
) {
    val isReadyForSpoofing: Boolean
        get() = isMockLocationAppSelected && hasFineLocationPermission
}

/**
 * Verifies AppOps mock location authorization and runtime permissions, and builds helper
 * intents / ADB setup commands for Developer Options.
 */
class MockLocationPermissionChecker(
    private val context: Context
) {
    fun getAdbMockLocationCommand(): String =
        buildAdbMockLocationCommand(context.packageName)

    fun checkStatus(): MockLocationPermissionStatus {
        return MockLocationPermissionStatus(
            isMockLocationAppSelected = isMockLocationAllowed(),
            hasFineLocationPermission = hasFineLocationPermission(),
            hasNotificationPermission = hasNotificationPermission(),
            adbCommand = getAdbMockLocationCommand()
        )
    }

    fun isMockLocationAllowed(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Deep link intent to Android Developer Options -> "Select mock location app".
     */
    fun buildDeveloperOptionsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        fun buildAdbMockLocationCommand(packageName: String): String =
            "adb shell appops set $packageName android:mock_location allow"
    }
}
