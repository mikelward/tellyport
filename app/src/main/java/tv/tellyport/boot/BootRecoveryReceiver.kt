package tv.tellyport.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.tellyport.TellyportApplication

/**
 * Listens for [Intent.ACTION_BOOT_COMPLETED] and triggers [ShizukuBootHelper] to re-enable
 * Wireless Debugging (`adb_wifi_enabled`) and recover Shizuku automatically after device reboots.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as? TellyportApplication ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val helper = ShizukuBootHelper(
                    platform = AndroidBootRecoveryPlatform(app),
                    recordDiagnostics = { diagnostics ->
                        app.profileRepository.recordBootRecoveryDiagnostics(diagnostics)
                    }
                )
                helper.runBootRecovery()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
