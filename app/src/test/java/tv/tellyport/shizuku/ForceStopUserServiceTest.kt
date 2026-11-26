package tv.tellyport.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceStopUserServiceTest {

    @Test
    fun packageNameRegex_acceptsValidAndroidPackageNames() {
        assertTrue(ForceStopUserService.PACKAGE_NAME_REGEX.matches("com.cbs.ott"))
        assertTrue(ForceStopUserService.PACKAGE_NAME_REGEX.matches("uk.co.bbc.iplayer"))
        assertTrue(ForceStopUserService.PACKAGE_NAME_REGEX.matches("au.com.mi9.jumpin.app"))
        assertTrue(ForceStopUserService.PACKAGE_NAME_REGEX.matches("tv.tellyport.test_pkg2"))
    }

    @Test
    fun packageNameRegex_rejectsInvalidOrInjectionPayloads() {
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches(""))
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches("singleSegment"))
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches("com.cbs.ott; id"))
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches("com.cbs.ott\nreboot"))
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches("--user 0 com.cbs.ott"))
        assertFalse(ForceStopUserService.PACKAGE_NAME_REGEX.matches("com.cbs.ott$(id)"))
    }
}
