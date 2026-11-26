package tv.tellyport.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Listens for [Intent.ACTION_BOOT_COMPLETED] and immediately schedules [BootRecoveryJobService]
 * via [android.app.job.JobScheduler] so post-boot network waiting, Wireless Debugging recovery,
 * and Shizuku wake-up run safely outside the broadcast receiver ANR deadline.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        BootRecoveryJobService.schedule(context.applicationContext)
    }
}
