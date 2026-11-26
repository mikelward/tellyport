package tv.tellyport.boot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.tellyport.TellyportApplication

/**
 * System-managed [JobService] scheduled by [BootRecoveryReceiver] so the up-to-60-second
 * Wi-Fi/Ethernet wait and Wireless Debugging / Shizuku recovery run completely outside the
 * `BOOT_COMPLETED` broadcast receiver ANR deadline.
 */
class BootRecoveryJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recoveryJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? TellyportApplication ?: return false
        recoveryJob?.cancel()
        recoveryJob = jobScope.launch {
            try {
                val helper = ShizukuBootHelper(
                    platform = AndroidBootRecoveryPlatform(app),
                    recordDiagnostics = { diagnostics ->
                        app.profileRepository.recordBootRecoveryDiagnostics(diagnostics)
                    }
                )
                helper.runBootRecovery()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        recoveryJob?.cancel()
        recoveryJob = null
        return false
    }

    override fun onDestroy() {
        recoveryJob?.cancel()
        jobScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 4202

        fun schedule(context: Context): Boolean {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return false
            val jobInfo = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, BootRecoveryJobService::class.java)
            )
                .setOverrideDeadline(0L)
                .build()
            return scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
        }
    }
}
