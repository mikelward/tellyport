package tv.tellyport.shizuku

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import tv.tellyport.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Uses a Shizuku [ForceStopUserService] running in Shizuku's privileged shell process
 * (no Android 12+ hidden-API client reflection required) to cleanly force-stop a streaming app
 * so it drops cached geo/IP state, falling back to [ActivityManager.killBackgroundProcesses]
 * when Shizuku is offline or unauthorized.
 */
class ShizukuForceStopper(
    private val context: Context
) : PackageForceStopper {

    private val appContext = context.applicationContext
    private val bindLock = Any()

    @Volatile
    private var boundService: IForceStopService? = null
    private var pendingBindLatch: CountDownLatch? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ForceStopUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_forcestop")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(bindLock) {
                boundService = if (binder != null && binder.pingBinder()) {
                    IForceStopService.Stub.asInterface(binder)
                } else {
                    null
                }
                pendingBindLatch?.countDown()
                pendingBindLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                boundService = null
                pendingBindLatch?.countDown()
                pendingBindLatch = null
            }
        }
    }

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

        // 1. Primary path: Shizuku UserService executing `am force-stop <packageName>` in shell UID.
        if (tryShizukuUserServiceForceStop(target)) {
            return ForceStopResult.SHIZUKU_FORCE_STOPPED
        }

        // 2. Fallback path: ActivityManager.killBackgroundProcesses (paired with FLAG_ACTIVITY_CLEAR_TASK on relaunch).
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return ForceStopResult.FAILED
            am.killBackgroundProcesses(target)
            ForceStopResult.FALLBACK_BACKGROUND_KILLED
        } catch (_: Throwable) {
            ForceStopResult.FAILED
        }
    }

    private fun tryShizukuUserServiceForceStop(packageName: String): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false

            val service = getOrBindUserService() ?: return false
            service.forceStopPackage(packageName)
        } catch (_: Throwable) {
            synchronized(bindLock) {
                boundService = null
            }
            false
        }
    }

    private fun getOrBindUserService(): IForceStopService? {
        val existing = boundService
        if (existing != null && existing.asBinder().pingBinder()) {
            return existing
        }

        val latch: CountDownLatch
        var shouldBind = false
        synchronized(bindLock) {
            val rechecked = boundService
            if (rechecked != null && rechecked.asBinder().pingBinder()) {
                return rechecked
            }
            boundService = null
            val currentLatch = pendingBindLatch
            if (currentLatch != null) {
                latch = currentLatch
            } else {
                latch = CountDownLatch(1)
                pendingBindLatch = latch
                shouldBind = true
            }
        }

        if (shouldBind) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (_: Throwable) {
                synchronized(bindLock) {
                    if (pendingBindLatch === latch) {
                        pendingBindLatch = null
                    }
                }
                latch.countDown()
                return null
            }
        }

        val completed = latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            synchronized(bindLock) {
                if (pendingBindLatch === latch) {
                    pendingBindLatch = null
                }
            }
        }
        return boundService?.takeIf { it.asBinder().pingBinder() }
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
        private const val BIND_TIMEOUT_MS = 2_000L
    }
}
