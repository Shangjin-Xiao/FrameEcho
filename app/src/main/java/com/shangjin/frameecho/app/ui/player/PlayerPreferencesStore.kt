package com.shangjin.frameecho.app.ui.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class PersistedPlayerSettings(
    val rememberQuickSettings: Boolean,
    val isMuted: Boolean,
    val motionPhoto: Boolean,
    val preserveMetadata: Boolean
)

/** Singleton DataStore instance for player quick settings. */
private val Context.playerPrefsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "player_quick_settings")

/**
 * Persistence for player quick toggles using Jetpack DataStore.
 *
 * DataStore replaces SharedPreferences with a coroutine-based, type-safe API
 * that avoids blocking the main thread on reads.
 *
 * @param dataStore The DataStore instance to use. Production code should use
 *   the convenience [Context] constructor; tests can inject an in-memory store.
 */
internal class PlayerPreferencesStore(private val dataStore: DataStore<Preferences>) {

    /** Convenience constructor for production use. */
    constructor(context: Context) : this(context.playerPrefsDataStore)

    suspend fun load(): PersistedPlayerSettings {
        return dataStore.data.map { prefs ->
            PersistedPlayerSettings(
                rememberQuickSettings = prefs[KEY_REMEMBER_QUICK_SETTINGS] ?: DEFAULT_REMEMBER_QUICK_SETTINGS,
                isMuted = prefs[KEY_IS_MUTED] ?: false,
                motionPhoto = prefs[KEY_MOTION_PHOTO] ?: false,
                preserveMetadata = prefs[KEY_PRESERVE_METADATA] ?: true
            )
        }.first()
    }

    suspend fun setRememberQuickSettings(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_REMEMBER_QUICK_SETTINGS] = enabled
        }
    }

    suspend fun saveQuickSettings(
        isMuted: Boolean,
        motionPhoto: Boolean,
        preserveMetadata: Boolean
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_MUTED] = isMuted
            prefs[KEY_MOTION_PHOTO] = motionPhoto
            prefs[KEY_PRESERVE_METADATA] = preserveMetadata
        }
    }

    private companion object {
        val KEY_REMEMBER_QUICK_SETTINGS = booleanPreferencesKey("remember_quick_settings")
        val KEY_IS_MUTED = booleanPreferencesKey("is_muted")
        val KEY_MOTION_PHOTO = booleanPreferencesKey("motion_photo")
        val KEY_PRESERVE_METADATA = booleanPreferencesKey("preserve_metadata")
        const val DEFAULT_REMEMBER_QUICK_SETTINGS = true
    }
}
