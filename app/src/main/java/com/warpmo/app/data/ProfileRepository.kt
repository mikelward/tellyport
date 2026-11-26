package com.warpmo.app.data

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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * JSON [Serializer] for persisting [ProfileStore] inside Jetpack DataStore.
 */
object ProfileStoreSerializer : Serializer<ProfileStore> {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: ProfileStore = ProfileStore(
        schemaVersion = 1,
        initialized = false,
        profiles = emptyList()
    )

    override suspend fun readFrom(input: InputStream): ProfileStore {
        val raw = input.readBytes().decodeToString()
        if (raw.isBlank()) return defaultValue
        return try {
            json.decodeFromString(ProfileStore.serializer(), raw)
        } catch (ex: SerializationException) {
            throw CorruptionException("Unable to deserialize Warpmo profiles store", ex)
        }
    }

    override suspend fun writeTo(t: ProfileStore, output: OutputStream) {
        val encoded = json.encodeToString(ProfileStore.serializer(), t)
        output.write(encoded.encodeToByteArray())
    }
}

/**
 * Repository managing user-configured [RegionalProfile] entries in Jetpack DataStore.
 * Automatically pre-seeds default profiles (US, UK, AU) on first access.
 */
class ProfileRepository(
    private val dataStore: DataStore<ProfileStore>
) {
    /**
     * Emits the current list of profiles, returning defaults until the store has been initialized.
     */
    val profiles: Flow<List<RegionalProfile>> = dataStore.data.map { store ->
        if (!store.initialized) {
            ProfilePresets.defaultProfiles()
        } else {
            store.profiles
        }
    }

    /**
     * Emits the persisted WG Tunnel remote-control authentication key.
     */
    val wgRemoteControlKey: Flow<String> = dataStore.data.map { it.wgRemoteControlKey }

    /**
     * Persists the WG Tunnel remote-control authentication key (copied from WG Tunnel Settings).
     */
    suspend fun setWgRemoteControlKey(key: String) {
        val trimmed = key.trim()
        dataStore.updateData { current ->
            current.copy(wgRemoteControlKey = trimmed)
        }
    }

    /**
     * Ensures the default profiles are written to disk on first launch so edits/deletions
     * operate on persisted state.
     */
    suspend fun ensureSeeded() {
        dataStore.updateData { current ->
            if (!current.initialized) {
                current.copy(
                    schemaVersion = 1,
                    initialized = true,
                    profiles = ProfilePresets.defaultProfiles()
                )
            } else {
                current
            }
        }
    }

    /**
     * Inserts a new profile or replaces an existing profile with the same [RegionalProfile.id].
     */
    suspend fun upsertProfile(profile: RegionalProfile) {
        dataStore.updateData { current ->
            val baseList = if (!current.initialized) {
                ProfilePresets.defaultProfiles()
            } else {
                current.profiles
            }
            val index = baseList.indexOfFirst { it.id == profile.id }
            val updated = if (index >= 0) {
                baseList.toMutableList().apply { set(index, profile) }
            } else {
                baseList + profile
            }
            current.copy(initialized = true, profiles = updated)
        }
    }

    /**
     * Deletes a profile by ID. Preserves an empty list if all profiles are deleted.
     */
    suspend fun deleteProfile(profileId: String) {
        dataStore.updateData { current ->
            val baseList = if (!current.initialized) {
                ProfilePresets.defaultProfiles()
            } else {
                current.profiles
            }
            current.copy(
                initialized = true,
                profiles = baseList.filterNot { it.id == profileId }
            )
        }
    }

    /**
     * Restores the built-in US, UK, and AU default profiles.
     */
    suspend fun resetDefaults() {
        dataStore.updateData { current ->
            current.copy(
                initialized = true,
                profiles = ProfilePresets.defaultProfiles()
            )
        }
    }

    companion object {
        private const val DATA_STORE_FILE_NAME = "warpmo_profiles.json"

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ): ProfileRepository {
            val dataStore = DataStoreFactory.create(
                serializer = ProfileStoreSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler {
                    ProfileStore(
                        schemaVersion = 1,
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
