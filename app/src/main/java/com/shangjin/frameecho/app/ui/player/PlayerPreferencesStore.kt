package com.shangjin.frameecho.app.ui.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class PersistedPlayerSettings(
    val rememberQuickSettings: Boolean = true,
    val isMuted: Boolean = false,
    val motionPhoto: Boolean = false,
    val preserveMetadata: Boolean = true,
    val format: String? = null,
    val quality: Int = 100,
    val customFileName: String? = null,
    val exportDirectory: String? = null,
    val customExportTreeUri: String? = null
)

/** Singleton DataStore instance for player quick settings. */
private val Context.playerPrefsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "player_quick_settings")

/**
 * Persistence for player quick toggles using Jetpack DataStore.
 *
 * DataStore replaces SharedPreferences with a coroutine-based, type-safe API
 * that avoids blocking the main thread on reads. For the initial load (called
 * from ViewModel.initialize), we use `runBlocking` because the ViewModel needs
 * the values synchronously before the first composition.
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
                preserveMetadata = prefs[KEY_PRESERVE_METADATA] ?: true,
                format = prefs[KEY_FORMAT],
                quality = prefs[KEY_QUALITY] ?: 100,
                customFileName = prefs[KEY_CUSTOM_FILE_NAME],
                exportDirectory = prefs[KEY_EXPORT_DIRECTORY],
                customExportTreeUri = prefs[KEY_CUSTOM_TREE_URI]
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
        preserveMetadata: Boolean,
        format: String? = null,
        quality: Int = 100,
        customFileName: String? = null,
        exportDirectory: String? = null,
        customExportTreeUri: String? = null
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_MUTED] = isMuted
            prefs[KEY_MOTION_PHOTO] = motionPhoto
            prefs[KEY_PRESERVE_METADATA] = preserveMetadata
            if (format != null) prefs[KEY_FORMAT] = format
            prefs[KEY_QUALITY] = quality
            if (customFileName != null) prefs[KEY_CUSTOM_FILE_NAME] = customFileName else prefs.remove(KEY_CUSTOM_FILE_NAME)
            if (exportDirectory != null) prefs[KEY_EXPORT_DIRECTORY] = exportDirectory
            if (customExportTreeUri != null) prefs[KEY_CUSTOM_TREE_URI] = customExportTreeUri else prefs.remove(KEY_CUSTOM_TREE_URI)
        }
    }

    private companion object {
        val KEY_REMEMBER_QUICK_SETTINGS = booleanPreferencesKey("remember_quick_settings")
        val KEY_IS_MUTED = booleanPreferencesKey("is_muted")
        val KEY_MOTION_PHOTO = booleanPreferencesKey("motion_photo")
        val KEY_PRESERVE_METADATA = booleanPreferencesKey("preserve_metadata")
        val KEY_FORMAT = stringPreferencesKey("format")
        val KEY_QUALITY = intPreferencesKey("quality")
        val KEY_CUSTOM_FILE_NAME = stringPreferencesKey("custom_file_name")
        val KEY_EXPORT_DIRECTORY = stringPreferencesKey("export_directory")
        val KEY_CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")
        const val DEFAULT_REMEMBER_QUICK_SETTINGS = true
    }
}
