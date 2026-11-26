package tv.tellyport.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProfileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepository(fileName: String, scope: CoroutineScope): ProfileRepository {
        val file = tempFolder.root.resolve(fileName)
        val dataStore = DataStoreFactory.create(
            serializer = ProfileStoreSerializer,
            scope = scope,
            produceFile = { file }
        )
        return ProfileRepository(dataStore)
    }

    @Test
    fun freshRepository_preSeedsAgnosticNewYorkLondonAndSydneyProfiles() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val repo = createRepository("seeded_profiles.json", scope)
            repo.ensureSeeded()

            val profiles = repo.profiles.first()
            assertEquals(3, profiles.size)
            assertEquals(listOf("New York", "London", "Sydney"), profiles.map { it.name })
            assertEquals(listOf("US", "UK", "AU"), profiles.map { it.regionCode })
            assertTrue(profiles.all { it.vpnBackend == VpnBackend.UNCONFIGURED })

            val ny = profiles.first { it.name == "New York" }
            assertEquals(40.7128, ny.latitude, 0.001)
            assertEquals(-74.0060, ny.longitude, 0.001)
            assertEquals(listOf("com.cbs.ott"), ny.mappedPackages)

            val london = profiles.first { it.name == "London" }
            assertEquals(51.5074, london.latitude, 0.001)
            assertEquals(-0.1278, london.longitude, 0.001)
            assertEquals(listOf("com.amazon.avod.thirdpartyclient", "uk.co.bbc.iplayer"), london.mappedPackages)

            val sydney = profiles.first { it.name == "Sydney" }
            assertEquals(-33.8688, sydney.latitude, 0.001)
            assertEquals(151.2093, sydney.longitude, 0.001)
            assertEquals(listOf("au.com.stan.and", "au.com.mi9.jumpin.app", "com.swm.live"), sydney.mappedPackages)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun singleTailscaleConstraint_allowsOneProfileAndRejectsSecondProfile() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val repo = createRepository("single_tailscale.json", scope)
            repo.ensureSeeded()

            val ny = repo.profiles.first().first { it.name == "New York" }
            val london = repo.profiles.first().first { it.name == "London" }

            // Assigning TAILSCALE to New York succeeds.
            repo.upsertProfile(ny.copy(vpnBackend = VpnBackend.TAILSCALE))
            assertEquals(VpnBackend.TAILSCALE, repo.profiles.first().first { it.id == ny.id }.vpnBackend)

            // Re-saving New York with TAILSCALE still succeeds.
            repo.upsertProfile(ny.copy(vpnBackend = VpnBackend.TAILSCALE, tunnelName = "us-nyc-exit"))

            // Assigning TAILSCALE to London while New York already uses it must fail.
            var caught: SingleTailscaleConstraintException? = null
            try {
                repo.upsertProfile(london.copy(vpnBackend = VpnBackend.TAILSCALE))
            } catch (ex: SingleTailscaleConstraintException) {
                caught = ex
            }
            assertNotNull("Expected SingleTailscaleConstraintException", caught)
            assertEquals(ny.id, caught!!.existingProfileId)
            assertEquals("New York", caught.existingProfileName)
            assertEquals(VpnBackend.UNCONFIGURED, repo.profiles.first().first { it.id == london.id }.vpnBackend)

            // Reassigning New York to WG_TUNNEL frees TAILSCALE for London.
            repo.upsertProfile(ny.copy(vpnBackend = VpnBackend.WG_TUNNEL, tunnelName = "us-wg"))
            repo.upsertProfile(london.copy(vpnBackend = VpnBackend.TAILSCALE))
            assertEquals(VpnBackend.TAILSCALE, repo.profiles.first().first { it.id == london.id }.vpnBackend)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun findProfileForPackage_mapsStreamingPackagesAccurately() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val repo = createRepository("package_lookup.json", scope)
            repo.ensureSeeded()

            assertEquals("New York", repo.findProfileForPackage("com.cbs.ott")?.name)
            assertEquals("London", repo.findProfileForPackage("uk.co.bbc.iplayer")?.name)
            assertEquals("London", repo.findProfileForPackage("com.amazon.avod.thirdpartyclient")?.name)
            assertEquals("Sydney", repo.findProfileForPackage("au.com.stan.and")?.name)
            assertEquals("Sydney", repo.findProfileForPackage("au.com.mi9.jumpin.app")?.name)
            assertEquals("Sydney", repo.findProfileForPackage("com.swm.live")?.name)
            assertNull(repo.findProfileForPackage("com.unknown.app"))
            assertNull(repo.findProfileForPackage("   "))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun activeSessionAutoDisconnectBootDiagnosticsAndProfiles_persistAcrossRestarts() = runTest {
        val fileName = "full_roundtrip.json"

        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(job1 + Dispatchers.IO)
        try {
            val repo1 = createRepository(fileName, scope1)
            repo1.ensureSeeded()

            repo1.setWgRemoteControlKey("  wg-key-42  ")
            repo1.setAutoDisconnectSettings(enabled = true, gracePeriodSeconds = 90)
            repo1.setActiveSession(
                ActiveVpnSession(
                    profileId = "preset-sydney",
                    vpnBackend = VpnBackend.WG_TUNNEL,
                    tunnelName = "unifi-au-syd",
                    activatedAtEpochMs = 1_730_000_000_000L
                )
            )
            repo1.recordBootRecoveryDiagnostics(
                BootRecoveryDiagnostics(
                    timestampEpochMs = 1_730_000_005_000L,
                    networkConnected = true,
                    networkType = "Ethernet",
                    writeSecureSettingsGranted = true,
                    adbWifiEnabled = true,
                    shizukuRunning = true,
                    shizukuStartAttempted = false,
                    message = "Network ready (Ethernet). Wireless Debugging enabled. Shizuku binder online."
                )
            )

            val customTokyo = RegionalProfile(
                id = "custom-jp",
                name = "Tokyo",
                regionCode = "JP",
                latitude = 35.6762,
                longitude = 139.6503,
                altitude = 40.0,
                accuracyMeters = 3.5f,
                vpnBackend = VpnBackend.SOCKS_WRAPPER,
                tunnelName = "jp-socks",
                mappedPackages = listOf("jp.nhk.plus"),
                autoLaunchApp = false
            )
            repo1.upsertProfile(customTokyo)
        } finally {
            job1.cancelAndJoin()
        }

        val job2 = SupervisorJob()
        val scope2 = CoroutineScope(job2 + Dispatchers.IO)
        try {
            val repo2 = createRepository(fileName, scope2)
            assertEquals("wg-key-42", repo2.wgRemoteControlKey.first())
            assertEquals(AutoDisconnectSettings(enabled = true, gracePeriodSeconds = 90), repo2.autoDisconnectSettings.first())
            val session = repo2.activeSession.first()
            assertNotNull(session)
            assertEquals("preset-sydney", session!!.profileId)
            assertEquals(VpnBackend.WG_TUNNEL, session.vpnBackend)
            assertEquals("unifi-au-syd", session.tunnelName)

            val boot = repo2.bootRecoveryDiagnostics.first()
            assertNotNull(boot)
            assertTrue(boot!!.adbWifiEnabled)
            assertEquals("Ethernet", boot.networkType)

            val tokyo = repo2.profiles.first().first { it.id == "custom-jp" }
            assertEquals(VpnBackend.SOCKS_WRAPPER, tokyo.vpnBackend)
            assertEquals(listOf("jp.nhk.plus"), tokyo.mappedPackages)
            assertFalse(tokyo.autoLaunchApp)
        } finally {
            job2.cancelAndJoin()
        }
    }

    @Test
    fun deletingAllProfiles_staysEmptyInsteadOfReSeeding() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val repo = createRepository("delete_all.json", scope)
            repo.ensureSeeded()
            repo.profiles.first().forEach { repo.deleteProfile(it.id) }

            assertTrue(repo.profiles.first().isEmpty())

            repo.resetDefaults()
            assertEquals(3, repo.profiles.first().size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun profileStoreSerializer_roundTripsAndRejectsCorruptJson() = runTest {
        val original = ProfileStore(
            schemaVersion = 2,
            initialized = true,
            wgRemoteControlKey = "key-xyz-999",
            activeSession = ActiveVpnSession("p-1", VpnBackend.TAILSCALE, null, 1234L),
            autoDisconnectSettings = AutoDisconnectSettings(enabled = true, gracePeriodSeconds = 45),
            profiles = listOf(
                RegionalProfile(
                    id = "p-1",
                    name = "Toronto",
                    regionCode = "CA",
                    latitude = 43.6532,
                    longitude = -79.3832,
                    altitude = 76.0,
                    accuracyMeters = 6.0f,
                    vpnBackend = VpnBackend.TAILSCALE,
                    tunnelName = null,
                    mappedPackages = listOf("ca.cbc.gem"),
                    autoLaunchApp = true
                )
            )
        )

        val output = ByteArrayOutputStream()
        ProfileStoreSerializer.writeTo(original, output)
        val decoded = ProfileStoreSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

        assertEquals(original, decoded)
        assertNull(decoded.profiles.single().tunnelName)

        var threwCorruption = false
        try {
            ProfileStoreSerializer.readFrom(ByteArrayInputStream("{not valid json".toByteArray()))
        } catch (_: CorruptionException) {
            threwCorruption = true
        }
        assertTrue("Expected CorruptionException for invalid JSON", threwCorruption)
    }
}
