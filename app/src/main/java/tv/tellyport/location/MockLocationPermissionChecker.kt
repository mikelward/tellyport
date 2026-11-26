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
import tv.tellyport.accessibility.AccessibilityServiceState

/**
 * Snapshot of location, mock-location, accessibility, and secure-settings prerequisites
 * required for zero-touch operation on Android 14 / Google TV.
 */
data class MockLocationPermissionStatus(
    val isMockLocationAppSelected: Boolean,
    val hasFineLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val isAccessibilityEnabled: Boolean = false,
    val hasWriteSecureSettingsPermission: Boolean = false,
    val adbCommand: String,
    val adbAccessibilityCommand: String = AccessibilityServiceState.buildAdbEnableCommand("tv.tellyport"),
    val adbWriteSecureSettingsCommand: String = MockLocationPermissionChecker.buildAdbWriteSecureSettingsCommand("tv.tellyport")
) {
    val isReadyForSpoofing: Boolean
        get() = isMockLocationAppSelected && hasFineLocationPermission
}

/**
 * Verifies AppOps mock location authorization, Accessibility Service binding, runtime permissions,
 * and `WRITE_SECURE_SETTINGS`, and builds settings deep links + copyable ADB setup commands.
 */
class MockLocationPermissionChecker(
    private val context: Context
) {
    fun getAdbMockLocationCommand(): String =
        buildAdbMockLocationCommand(context.packageName)

    fun getAdbAccessibilityCommand(): String =
        AccessibilityServiceState.buildAdbEnableCommand(context.packageName)

    fun getAdbWriteSecureSettingsCommand(): String =
        buildAdbWriteSecureSettingsCommand(context.packageName)

    fun checkStatus(): MockLocationPermissionStatus {
        return MockLocationPermissionStatus(
            isMockLocationAppSelected = isMockLocationAllowed(),
            hasFineLocationPermission = hasFineLocationPermission(),
            hasNotificationPermission = hasNotificationPermission(),
            isAccessibilityEnabled = AccessibilityServiceState.isEnabledInSettings(context),
            hasWriteSecureSettingsPermission = hasWriteSecureSettingsPermission(),
            adbCommand = getAdbMockLocationCommand(),
            adbAccessibilityCommand = getAdbAccessibilityCommand(),
            adbWriteSecureSettingsCommand = getAdbWriteSecureSettingsCommand()
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

    fun hasWriteSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildDeveloperOptionsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        fun buildAdbMockLocationCommand(packageName: String): String =
            "adb shell appops set $packageName android:mock_location allow"

        fun buildAdbWriteSecureSettingsCommand(packageName: String): String =
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
    }
}
