package com.shangjin.frameecho.app.ui.about

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Singleton DataStore instance for update preferences. */
private val Context.updateDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "update_prefs")

/**
 * Manages user preferences for the update checker.
 *
 * Stores a set of version tags that the user has chosen to permanently ignore.
 * Uses Jetpack DataStore for lightweight, type-safe persistence.
 */
class UpdatePreferences(context: Context) {

    private val dataStore = context.updateDataStore

    /**
     * Returns the set of permanently ignored version tags.
     */
    val permanentlyIgnoredVersions: Set<String>
        get() = runBlocking {
            dataStore.data.map { prefs ->
                prefs[KEY_IGNORED_VERSIONS] ?: emptySet()
            }.first()
        }

    /**
     * Adds a version tag to the permanently ignored set.
     */
    fun ignoreVersionPermanently(versionTag: String) = runBlocking {
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED_VERSIONS] ?: emptySet()
            prefs[KEY_IGNORED_VERSIONS] = current + versionTag
        }
    }

    /**
     * Checks whether a version tag has been permanently ignored.
     */
    fun isVersionPermanentlyIgnored(versionTag: String): Boolean =
        versionTag in permanentlyIgnoredVersions

    private companion object {
        val KEY_IGNORED_VERSIONS = stringSetPreferencesKey("ignored_versions")
    }
}
