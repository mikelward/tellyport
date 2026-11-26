package tv.tellyport.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.tellyport.TellyportApplication

/**
 * Live connectivity state of [AppLaunchAccessibilityService] observable by the UI.
 */
object AccessibilityServiceState {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    internal fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }

    /**
     * Checks both live service binding and `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        if (_isConnected.value) return true
        val expectedComponent = ComponentName(context, AppLaunchAccessibilityService::class.java)
            .flattenToString()
        val shortComponent = ComponentName(context, AppLaunchAccessibilityService::class.java)
            .flattenToShortString()
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
        return enabled.split(':').any { entry ->
            entry.equals(expectedComponent, ignoreCase = true) ||
                entry.equals(shortComponent, ignoreCase = true)
        }
    }

    fun buildAdbEnableCommand(packageName: String): String {
        return "adb shell settings put secure enabled_accessibility_services " +
            "$packageName/${AppLaunchAccessibilityService::class.java.name} && " +
            "adb shell settings put secure accessibility_enabled 1"
    }
}

/**
 * Minimalist [AccessibilityService] listening strictly to
 * [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] (with `canRetrieveWindowContent="false"`)
 * to silently intercept mapped streaming app launches on Google TV.
 */
class AppLaunchAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: AppLaunchInterceptionEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceState.setConnected(true)

        val app = applicationContext as? TellyportApplication ?: return
        val resolvedHomePackage = resolveDefaultHomePackage()

        engine = AppLaunchInterceptionEngine(
            scope = serviceScope,
            profilesProvider = { app.profileRepository.getProfilesSnapshot() },
            autoDisconnectSettingsProvider = { app.profileRepository.getAutoDisconnectSettingsSnapshot() },
            hasActiveSessionOrVpn = {
                app.vpnNetworkMonitor.refreshState().isConnected ||
                    app.profileRepository.getActiveSessionSnapshot() != null ||
                    tv.tellyport.location.MockLocationState.status.value.isActive
            },
            coordinator = app.activationCoordinator,
            forceStopper = app.shizukuForceStopper,
            performGlobalHomeAction = { performGlobalAction(GLOBAL_ACTION_HOME) },
            additionalLauncherPackages = setOfNotNull(resolvedHomePackage)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val pkg = event.packageName?.toString() ?: return
        val currentEngine = engine ?: return

        serviceScope.launch {
            currentEngine.handleEvent(event.eventType, pkg)
        }
    }

    override fun onInterrupt() {
        engine?.cancelHomeAutoDisconnectTimer()
    }

    override fun onDestroy() {
        engine?.cancelHomeAutoDisconnectTimer()
        engine = null
        AccessibilityServiceState.setConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resolveDefaultHomePackage(): String? {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            @Suppress("DEPRECATION")
            val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
