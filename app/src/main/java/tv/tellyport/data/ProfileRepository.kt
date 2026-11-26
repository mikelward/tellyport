package tv.tellyport.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream

/**
 * Thrown when saving a profile with [VpnBackend.TAILSCALE] while another profile already
 * uses external Tailscale automation.
 */
class SingleTailscaleConstraintException(
    val existingProfileId: String,
    val existingProfileName: String
) : IllegalStateException(
    "Only one profile can use external Tailscale automation at a time (already assigned to $existingProfileName). Assign WG Tunnel (or another backend) to additional locations."
)

/**
 * JSON [Serializer] for persisting [ProfileStore] inside Jetpack DataStore.
 * Automatically migrates v1 schema fields (`vpnTunnelOrNodeName` -> `tunnelName`,
 * `targetAppPackage` -> `mappedPackages`) so upgrading users never lose saved configuration.
 */
object ProfileStoreSerializer : Serializer<ProfileStore> {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: ProfileStore = ProfileStore(
        schemaVersion = 2,
        initialized = false,
        profiles = emptyList()
    )

    override suspend fun readFrom(input: InputStream): ProfileStore {
        val raw = input.readBytes().decodeToString()
        if (raw.isBlank()) return defaultValue
        return try {
            val parsed = json.parseToJsonElement(raw)
            val migrated = migrateLegacyStoreJson(parsed)
            json.decodeFromJsonElement(ProfileStore.serializer(), migrated)
        } catch (ex: SerializationException) {
            throw CorruptionException("Unable to deserialize Tellyport profiles store", ex)
        } catch (ex: IllegalArgumentException) {
            throw CorruptionException("Unable to deserialize Tellyport profiles store", ex)
        }
    }

    private fun migrateLegacyStoreJson(element: JsonElement): JsonElement {
        val root = element as? JsonObject ?: return element
        val rawProfiles = root["profiles"] as? JsonArray ?: return root

        val migratedProfiles = JsonArray(
            rawProfiles.map { entry ->
                val profileObj = entry as? JsonObject ?: return@map entry
                migrateLegacyProfileJson(profileObj)
            }
        )

        return buildJsonObject {
            root.forEach { (key, value) ->
                if (key == "profiles") {
                    put("profiles", migratedProfiles)
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun migrateLegacyProfileJson(profile: JsonObject): JsonObject {
        val existingTunnel = (profile["tunnelName"] as? JsonPrimitive)?.contentOrNull?.trim()
        val legacyTunnel = (profile["vpnTunnelOrNodeName"] as? JsonPrimitive)?.contentOrNull?.trim()

        val existingMapped = (profile["mappedPackages"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val legacyTargetApp = (profile["targetAppPackage"] as? JsonPrimitive)?.contentOrNull?.trim()

        val needsTunnelMigration = existingTunnel.isNullOrEmpty() && !legacyTunnel.isNullOrEmpty()
        val needsAppMigration = existingMapped.isEmpty() && !legacyTargetApp.isNullOrEmpty()

        if (!needsTunnelMigration && !needsAppMigration) {
            return profile
        }

        return buildJsonObject {
            profile.forEach { (key, value) ->
                put(key, value)
            }
            if (needsTunnelMigration) {
                put("tunnelName", JsonPrimitive(legacyTunnel))
            }
            if (needsAppMigration) {
                put("mappedPackages", JsonArray(listOf(JsonPrimitive(legacyTargetApp))))
            }
        }
    }

    override suspend fun writeTo(t: ProfileStore, output: OutputStream) {
        val encoded = json.encodeToString(ProfileStore.serializer(), t)
        output.write(encoded.encodeToByteArray())
    }
}

/**
 * Repository managing user-configured [RegionalProfile] entries, active VPN session recovery,
 * auto-disconnect settings, WG Tunnel key, and boot diagnostics in Jetpack DataStore.
 */
class ProfileRepository(
    private val dataStore: DataStore<ProfileStore>
) {
    val store: Flow<ProfileStore> = dataStore.data.map { current ->
        if (!current.initialized) {
            current.copy(profiles = ProfilePresets.defaultProfiles())
        } else {
            current
        }
    }

    val profiles: Flow<List<RegionalProfile>> = store.map { it.profiles }

    val wgRemoteControlKey: Flow<String> = dataStore.data.map { it.wgRemoteControlKey }

    val activeSession: Flow<ActiveVpnSession?> = dataStore.data.map { it.activeSession }

    val autoDisconnectSettings: Flow<AutoDisconnectSettings> =
        dataStore.data.map { it.autoDisconnectSettings }

    val bootRecoveryDiagnostics: Flow<BootRecoveryDiagnostics?> =
        dataStore.data.map { it.bootRecoveryDiagnostics }

    suspend fun getActiveSessionSnapshot(): ActiveVpnSession? = dataStore.data.first().activeSession

    suspend fun getProfilesSnapshot(): List<RegionalProfile> = profiles.first()

    suspend fun getAutoDisconnectSettingsSnapshot(): AutoDisconnectSettings =
        autoDisconnectSettings.first()

    /**
     * Ensures the default agnostic profiles (New York, London, Sydney) are persisted on first run.
     */
    suspend fun ensureSeeded() {
        dataStore.updateData { current ->
            if (!current.initialized) {
                current.copy(
                    schemaVersion = 2,
                    initialized = true,
                    profiles = ProfilePresets.defaultProfiles()
                )
            } else {
                current
            }
        }
    }

    /**
     * Inserts or updates a profile, enforcing the single-[VpnBackend.TAILSCALE] constraint
     * across all persisted profiles.
     *
     * @throws SingleTailscaleConstraintException if another profile already uses [VpnBackend.TAILSCALE].
     */
    suspend fun upsertProfile(profile: RegionalProfile) {
        val normalized = profile.copy(
            name = profile.name.trim(),
            regionCode = profile.regionCode.trim().uppercase(),
            tunnelName = profile.tunnelName?.trim()?.ifBlank { null },
            mappedPackages = profile.mappedPackages
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        )

        dataStore.updateData { current ->
            val baseList = if (!current.initialized) {
                ProfilePresets.defaultProfiles()
            } else {
                current.profiles
            }

            if (normalized.vpnBackend == VpnBackend.TAILSCALE) {
                val conflicting = baseList.firstOrNull {
                    it.id != normalized.id && it.vpnBackend == VpnBackend.TAILSCALE
                }
                if (conflicting != null) {
                    throw SingleTailscaleConstraintException(
                        existingProfileId = conflicting.id,
                        existingProfileName = conflicting.name
                    )
                }
            }

            val index = baseList.indexOfFirst { it.id == normalized.id }
            val updated = if (index >= 0) {
                baseList.toMutableList().apply { set(index, normalized) }
            } else {
                baseList + normalized
            }
            current.copy(schemaVersion = 2, initialized = true, profiles = updated)
        }
    }

    /**
     * Deletes a profile by ID. Clears [ProfileStore.activeSession] if it referenced the deleted profile.
     */
    suspend fun deleteProfile(profileId: String) {
        dataStore.updateData { current ->
            val baseList = if (!current.initialized) {
                ProfilePresets.defaultProfiles()
            } else {
                current.profiles
            }
            val updatedSession = current.activeSession?.takeUnless { it.profileId == profileId }
            current.copy(
                initialized = true,
                profiles = baseList.filterNot { it.id == profileId },
                activeSession = updatedSession
            )
        }
    }

    /**
     * Restores the built-in New York, London, and Sydney agnostic profiles.
     */
    suspend fun resetDefaults() {
        dataStore.updateData { current ->
            current.copy(
                schemaVersion = 2,
                initialized = true,
                profiles = ProfilePresets.defaultProfiles(),
                activeSession = null
            )
        }
    }

    suspend fun setWgRemoteControlKey(key: String) {
        val trimmed = key.trim()
        dataStore.updateData { current ->
            current.copy(wgRemoteControlKey = trimmed)
        }
    }

    suspend fun setActiveSession(session: ActiveVpnSession?) {
        dataStore.updateData { current ->
            current.copy(activeSession = session)
        }
    }

    suspend fun clearActiveSession() {
        setActiveSession(null)
    }

    suspend fun setAutoDisconnectSettings(enabled: Boolean, gracePeriodSeconds: Int) {
        val clampedGrace = gracePeriodSeconds.coerceIn(5, 600)
        dataStore.updateData { current ->
            current.copy(
                autoDisconnectSettings = AutoDisconnectSettings(
                    enabled = enabled,
                    gracePeriodSeconds = clampedGrace
                )
            )
        }
    }

    suspend fun recordBootRecoveryDiagnostics(diagnostics: BootRecoveryDiagnostics) {
        dataStore.updateData { current ->
            current.copy(bootRecoveryDiagnostics = diagnostics)
        }
    }

    /**
     * Looks up the [RegionalProfile] mapped to [packageName], or `null` if unmapped.
     */
    suspend fun findProfileForPackage(packageName: String): RegionalProfile? {
        return findProfileForPackage(getProfilesSnapshot(), packageName)
    }

    companion object {
        private const val DATA_STORE_FILE_NAME = "tellyport_profiles.json"

        const val SINGLE_TAILSCALE_MESSAGE =
            "Only one profile can use external Tailscale automation at a time. Assign WG Tunnel (or another backend) to additional locations."

        /**
         * Pure package-to-profile matcher used by both [ProfileRepository] and
         * [tv.tellyport.accessibility.AppLaunchInterceptionEngine].
         */
        fun findProfileForPackage(
            profiles: List<RegionalProfile>,
            packageName: String?
        ): RegionalProfile? {
            val target = packageName?.trim().orEmpty()
            if (target.isEmpty()) return null
            return profiles.firstOrNull { profile ->
                profile.mappedPackages.any { it.equals(target, ignoreCase = false) }
            }
        }

        /**
         * Finds an existing profile using [VpnBackend.TAILSCALE], optionally excluding [excludingProfileId].
         */
        fun findTailscaleProfile(
            profiles: List<RegionalProfile>,
            excludingProfileId: String? = null
        ): RegionalProfile? {
            return profiles.firstOrNull {
                it.vpnBackend == VpnBackend.TAILSCALE && it.id != excludingProfileId
            }
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ): ProfileRepository {
            val dataStore = DataStoreFactory.create(
                serializer = ProfileStoreSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler {
                    ProfileStore(
                        schemaVersion = 2,
                        initialized = true,
                        profiles = ProfilePresets.defaultProfiles()
                    )
                },
                scope = scope,
                produceFile = { context.applicationContext.dataStoreFile(DATA_STORE_FILE_NAME) }
            )
            return ProfileRepository(dataStore)
        }
    }
}
