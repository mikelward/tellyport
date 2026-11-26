package tv.tellyport.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend

class AppFuzzyMatcherTest {

    private val nyProfile = RegionalProfile(
        id = "preset-ny",
        name = "New York",
        regionCode = "US",
        latitude = 40.7128,
        longitude = -74.0060,
        vpnBackend = VpnBackend.TAILSCALE,
        mappedPackages = listOf("com.cbs.ott")
    )

    private val londonProfile = RegionalProfile(
        id = "preset-london",
        name = "London",
        regionCode = "UK",
        latitude = 51.5074,
        longitude = -0.1278,
        vpnBackend = VpnBackend.WG_TUNNEL,
        tunnelName = "uk-lon-wg",
        mappedPackages = listOf("uk.co.bbc.iplayer", "com.amazon.avod.thirdpartyclient")
    )

    private val sydneyProfile = RegionalProfile(
        id = "preset-sydney",
        name = "Sydney",
        regionCode = "AU",
        latitude = -33.8688,
        longitude = 151.2093,
        vpnBackend = VpnBackend.WG_TUNNEL,
        tunnelName = "au-syd-wg",
        mappedPackages = listOf("au.com.stan.and")
    )

    private val installedApps = listOf(
        InstalledTvApp("BBC iPlayer", "uk.co.bbc.iplayer"),
        InstalledTvApp("Netflix", "com.netflix.ninja"),
        InstalledTvApp("Paramount+", "com.cbs.ott"),
        InstalledTvApp("Prime Video", "com.amazon.avod.thirdpartyclient"),
        InstalledTvApp("YouTube", "com.google.android.youtube.tv")
    )

    @Test
    fun buildConfigurableAppList_mergesInstalledAndUninstalledMappedAppsSortedAlphabetically() {
        val entries = AppFuzzyMatcher.buildConfigurableAppList(
            installedApps = installedApps,
            profiles = listOf(nyProfile, londonProfile, sydneyProfile)
        )

        assertEquals(
            listOf("BBC iPlayer", "Netflix", "Paramount+", "Prime Video", "Stan", "YouTube"),
            entries.map { it.label }
        )
        val stan = entries.first { it.packageName == "au.com.stan.and" }
        assertFalse(stan.isInstalled)
        assertEquals("preset-sydney", stan.assignedProfile?.id)

        val netflix = entries.first { it.packageName == "com.netflix.ninja" }
        assertTrue(netflix.isInstalled)
        assertNull(netflix.assignedProfile)
    }

    @Test
    fun filterAndRank_supportsFuzzySubsequenceTypoMultiTokenAndCountryMetadata() {
        val entries = AppFuzzyMatcher.buildConfigurableAppList(
            installedApps = installedApps,
            profiles = listOf(nyProfile, londonProfile, sydneyProfile)
        )

        // Subsequence fuzzy match ("iplr" -> BBC iPlayer)
        val subsequence = AppFuzzyMatcher.filterAndRank(entries, "iplr")
        assertEquals(listOf("BBC iPlayer"), subsequence.map { it.label })

        // Single-edit typo tolerance ("ipalyer" -> BBC iPlayer, "netflx" -> Netflix)
        val typoTransposition = AppFuzzyMatcher.filterAndRank(entries, "ipalyer")
        assertEquals(listOf("BBC iPlayer"), typoTransposition.map { it.label })

        val typoDeletion = AppFuzzyMatcher.filterAndRank(entries, "netflx")
        assertEquals(listOf("Netflix"), typoDeletion.map { it.label })

        // Multi-token match across name + country ("bbc uk" -> BBC iPlayer)
        val multiToken = AppFuzzyMatcher.filterAndRank(entries, "bbc uk")
        assertEquals(listOf("BBC iPlayer"), multiToken.map { it.label })

        // Package name match ("cbs" -> Paramount+)
        val packageMatch = AppFuzzyMatcher.filterAndRank(entries, "cbs")
        assertEquals(listOf("Paramount+"), packageMatch.map { it.label })

        // Country / VPN backend metadata filter ("tailscale" -> Paramount+)
        val backendMatch = AppFuzzyMatcher.filterAndRank(entries, "tailscale")
        assertEquals(listOf("Paramount+"), backendMatch.map { it.label })

        // Non-matching query returns empty list
        assertTrue(AppFuzzyMatcher.filterAndRank(entries, "zzzznotanapp").isEmpty())
    }

    @Test
    fun filterAndRank_ranksDirectPrefixHigherThanLooseFuzzyMatches() {
        val entries = listOf(
            ConfigurableAppEntry("Super Prime Sports", "com.sports.super", true, null),
            ConfigurableAppEntry("Prime Video", "com.amazon.avod.thirdpartyclient", true, londonProfile),
            ConfigurableAppEntry("Player Room", "com.room.player", true, null)
        )

        val ranked = AppFuzzyMatcher.filterAndRank(entries, "prime")
        assertEquals("Prime Video", ranked.first().label)
        assertEquals(listOf("Prime Video", "Super Prime Sports"), ranked.map { it.label })
    }

    @Test
    fun filterAndRank_respectsMappedAndUnmappedFilterModes() {
        val entries = AppFuzzyMatcher.buildConfigurableAppList(
            installedApps = installedApps,
            profiles = listOf(nyProfile, londonProfile, sydneyProfile)
        )

        val mapped = AppFuzzyMatcher.filterAndRank(entries, "", AppFilterMode.MAPPED)
        assertEquals(4, mapped.size)
        assertTrue(mapped.all { it.assignedProfile != null })

        val unmapped = AppFuzzyMatcher.filterAndRank(entries, "", AppFilterMode.UNMAPPED)
        assertEquals(listOf("Netflix", "YouTube"), unmapped.map { it.label })
    }
}
