package com.warpmo.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun freshRepository_preSeedsUsUkAndAuProfiles() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createRepository("seeded_profiles.json", scope)
            repo.ensureSeeded()

            val profiles = repo.profiles.first()
            assertEquals(3, profiles.size)
            assertEquals(listOf("US", "UK", "AU"), profiles.map { it.regionCode })

            val us = profiles.first { it.regionCode == "US" }
            assertEquals(40.7128, us.latitude, 0.001)
            assertEquals(-74.0060, us.longitude, 0.001)

            val uk = profiles.first { it.regionCode == "UK" }
            assertEquals(51.5074, uk.latitude, 0.001)
            assertEquals(-0.1278, uk.longitude, 0.001)

            val au = profiles.first { it.regionCode == "AU" }
            assertEquals(-33.8688, au.latitude, 0.001)
            assertEquals(151.2093, au.longitude, 0.001)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun upsertEditDeleteAndReload_persistsJsonAcrossRepositoryInstances() = runTest {
        val fileName = "roundtrip_profiles.json"

        // First session: seed, add custom profile, edit UK profile, delete AU profile.
        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(job1 + Dispatchers.IO)
        try {
            val repo1 = createRepository(fileName, scope1)
            repo1.ensureSeeded()

            val customJapan = RegionalProfile(
                id = "custom-jp",
                name = "Japan Tokyo",
                regionCode = "JP",
                latitude = 35.6762,
                longitude = 139.6503,
                altitude = 40.0,
                accuracyMeters = 3.5f,
                vpnBackend = VpnBackend.WG_TUNNEL,
                vpnTunnelOrNodeName = "unifi-jp-tyo",
                targetAppPackage = "com.amazon.avod.thirdpartyclient",
                autoLaunchApp = false
            )
            repo1.upsertProfile(customJapan)

            val editedUk = repo1.profiles.first().first { it.regionCode == "UK" }.copy(
                name = "UK London Updated",
                vpnTunnelOrNodeName = "uk-lon-02"
            )
            repo1.upsertProfile(editedUk)

            val auId = repo1.profiles.first().first { it.regionCode == "AU" }.id
            repo1.deleteProfile(auId)
        } finally {
            job1.cancelAndJoin()
        }

        // Second session: reopen DataStore from the same file on disk and verify persistence.
        val job2 = SupervisorJob()
        val scope2 = CoroutineScope(job2 + Dispatchers.IO)
        try {
            val repo2 = createRepository(fileName, scope2)
            val persisted = repo2.profiles.first()

            assertEquals(3, persisted.size)
            assertTrue(persisted.none { it.regionCode == "AU" })

            val uk = persisted.first { it.regionCode == "UK" }
            assertEquals("UK London Updated", uk.name)
            assertEquals("uk-lon-02", uk.vpnTunnelOrNodeName)

            val jp = persisted.first { it.id == "custom-jp" }
            assertEquals("Japan Tokyo", jp.name)
            assertEquals(VpnBackend.WG_TUNNEL, jp.vpnBackend)
            assertEquals("unifi-jp-tyo", jp.vpnTunnelOrNodeName)
            assertEquals("com.amazon.avod.thirdpartyclient", jp.targetAppPackage)
            assertFalse(jp.autoLaunchApp)
        } finally {
            job2.cancelAndJoin()
        }
    }

    @Test
    fun deletingAllProfiles_staysEmptyInsteadOfReSeeding() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createRepository("delete_all.json", scope)
            repo.ensureSeeded()
            repo.profiles.first().forEach { repo.deleteProfile(it.id) }

            assertTrue(repo.profiles.first().isEmpty())

            repo.resetDefaults()
            assertEquals(3, repo.profiles.first().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun wgRemoteControlKey_persistsAcrossRepositoryInstances() = runTest {
        val fileName = "wg_key_store.json"
        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(job1 + Dispatchers.IO)
        try {
            val repo1 = createRepository(fileName, scope1)
            repo1.ensureSeeded()
            assertEquals("", repo1.wgRemoteControlKey.first())

            repo1.setWgRemoteControlKey("  my-wg-secret-key  ")
            assertEquals("my-wg-secret-key", repo1.wgRemoteControlKey.first())
        } finally {
            job1.cancelAndJoin()
        }

        val job2 = SupervisorJob()
        val scope2 = CoroutineScope(job2 + Dispatchers.IO)
        try {
            val repo2 = createRepository(fileName, scope2)
            assertEquals("my-wg-secret-key", repo2.wgRemoteControlKey.first())
        } finally {
            job2.cancelAndJoin()
        }
    }

    @Test
    fun profileStoreSerializer_roundTripsNullableFieldsAndRejectsCorruptJson() = runTest {
        val original = ProfileStore(
            schemaVersion = 1,
            initialized = true,
            wgRemoteControlKey = "key-xyz-999",
            profiles = listOf(
                RegionalProfile(
                    id = "p-1",
                    name = "No VPN Test",
                    regionCode = "CA",
                    latitude = 43.6532,
                    longitude = -79.3832,
                    altitude = 76.0,
                    accuracyMeters = 6.0f,
                    vpnBackend = VpnBackend.NONE,
                    vpnTunnelOrNodeName = null,
                    targetAppPackage = null,
                    autoLaunchApp = true
                )
            )
        )

        val output = ByteArrayOutputStream()
        ProfileStoreSerializer.writeTo(original, output)
        val decoded = ProfileStoreSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

        assertEquals(original, decoded)
        assertEquals("key-xyz-999", decoded.wgRemoteControlKey)
        assertNull(decoded.profiles.single().vpnTunnelOrNodeName)
        assertNull(decoded.profiles.single().targetAppPackage)

        var threwCorruption = false
        try {
            ProfileStoreSerializer.readFrom(ByteArrayInputStream("{not valid json".toByteArray()))
        } catch (_: CorruptionException) {
            threwCorruption = true
        }
        assertTrue("Expected CorruptionException for invalid JSON", threwCorruption)
    }
}
