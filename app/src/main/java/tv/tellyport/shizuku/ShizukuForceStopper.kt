package tv.tellyport.shizuku

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Snapshot of Shizuku availability and authorization for diagnostics UI.
 */
data class ShizukuStatus(
    val isInstalled: Boolean,
    val isBinderAlive: Boolean,
    val isPermissionGranted: Boolean
) {
    val isReady: Boolean
        get() = isBinderAlive && isPermissionGranted
}

/**
 * Result of attempting to force-stop a streaming app before switching VPN/location.
 */
enum class ForceStopResult {
    SHIZUKU_FORCE_STOPPED,
    FALLBACK_BACKGROUND_KILLED,
    FAILED
}

/**
 * Abstraction for force-stopping a target package, cleanly mockable in unit tests.
 */
interface PackageForceStopper {
    fun forceStop(packageName: String): ForceStopResult
}

/**
 * Uses Shizuku's privileged shell binder (`IActivityManager.forceStopPackage`) to cleanly
 * terminate a streaming app so it drops cached geo/IP state, falling back to
 * [ActivityManager.killBackgroundProcesses] when Shizuku is offline or unauthorized.
 */
class ShizukuForceStopper(
    private val context: Context
) : PackageForceStopper {

    private val appContext = context.applicationContext

    fun checkStatus(): ShizukuStatus {
        val installed = isShizukuInstalled()
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        val permissionGranted = if (binderAlive) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        return ShizukuStatus(
            isInstalled = installed,
            isBinderAlive = binderAlive,
            isPermissionGranted = permissionGranted
        )
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(requestCode)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun forceStop(packageName: String): ForceStopResult {
        val target = packageName.trim()
        if (target.isEmpty()) return ForceStopResult.FAILED

        // 1. Primary path: Shizuku IActivityManager.forceStopPackage(packageName, userId)
        if (tryShizukuForceStop(target)) {
            return ForceStopResult.SHIZUKU_FORCE_STOPPED
        }

        // 2. Fallback path: ActivityManager.killBackgroundProcesses (paired with FLAG_ACTIVITY_CLEAR_TASK on relaunch)
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return ForceStopResult.FAILED
            am.killBackgroundProcesses(target)
            ForceStopResult.FALLBACK_BACKGROUND_KILLED
        } catch (_: Throwable) {
            ForceStopResult.FAILED
        }
    }

    private fun tryShizukuForceStop(packageName: String): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false

            val rawBinder = SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)
                ?: return false
            val shizukuBinder: IBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val activityManager = asInterface.invoke(null, shizukuBinder) ?: return false

            val userId = Process.myUserHandle().hashCode()
            val forceStopMethod = activityManager.javaClass.getMethod(
                "forceStopPackage",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            forceStopMethod.invoke(activityManager, packageName, userId)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE_SHIZUKU = 4210
    }
}
