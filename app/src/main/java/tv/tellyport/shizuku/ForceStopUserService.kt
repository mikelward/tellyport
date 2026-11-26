package tv.tellyport.shizuku

import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService executing inside Shizuku's privileged shell process (UID 2000),
 * avoiding Android 12+ hidden-API client restrictions on `IActivityManager.Stub.asInterface`.
 */
@Keep
class ForceStopUserService : IForceStopService.Stub() {

    override fun forceStopPackage(packageName: String?): Boolean {
        val target = packageName?.trim().orEmpty()
        if (!PACKAGE_NAME_REGEX.matches(target)) return false

        return try {
            val devNull = File("/dev/null")
            val process = ProcessBuilder("am", "force-stop", target)
                .redirectOutput(devNull)
                .redirectError(devNull)
                .start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 3L
        internal val PACKAGE_NAME_REGEX =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
