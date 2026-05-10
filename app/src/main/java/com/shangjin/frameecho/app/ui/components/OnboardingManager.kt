package com.shangjin.frameecho.app.ui.components

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Singleton DataStore instance for onboarding state. */
private val Context.onboardingDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "onboarding_prefs")

/**
 * Manages onboarding state with per-step tracking.
 *
 * Each onboarding step has a unique string key. When the user sees a step,
 * its key is added to a persisted set. This allows new steps to be introduced
 * in future app updates without replaying steps the user has already seen.
 */
class OnboardingManager(context: Context) {

    private val dataStore = context.onboardingDataStore

    /** Set of step keys that the user has already seen. */
    private suspend fun getSeenStepKeys(): Set<String> =
        dataStore.data.map { prefs ->
            prefs[KEY_SEEN_STEPS] ?: emptySet()
        }.first()

    /**
     * Legacy check: whether the user completed the old (pre-versioned) onboarding.
     * Used for migration so existing users don't see old steps again.
     */
    private suspend fun hasLegacyCompleted(): Boolean =
        dataStore.data.map { prefs ->
            // The old key was a boolean "onboarding_completed"
            prefs[LEGACY_KEY_COMPLETED] ?: false
        }.first()

    /**
     * Returns the list of step keys that the user has NOT yet seen.
     * If the user completed the legacy onboarding, all legacy steps are
     * considered seen; only genuinely new steps are returned.
     */
    suspend fun getUnseenStepKeys(allStepKeys: List<String>, legacyStepKeys: Set<String>): List<String> {
        val seen = getSeenStepKeys()
        // If user completed legacy onboarding, treat all legacy steps as seen
        val effectivelySeen = if (hasLegacyCompleted()) seen + legacyStepKeys else seen
        return allStepKeys.filter { it !in effectivelySeen }
    }

    /**
     * Mark a set of step keys as seen.
     */
    suspend fun markStepsSeen(keys: Set<String>) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SEEN_STEPS] ?: emptySet()
            prefs[KEY_SEEN_STEPS] = current + keys
        }
    }

    /**
     * Convenience: mark all steps as seen (equivalent to completing onboarding).
     */
    suspend fun markAllSeen(allStepKeys: List<String>) {
        markStepsSeen(allStepKeys.toSet())
    }

    /**
     * Legacy compat: mark old boolean as completed too.
     */
    suspend fun markOnboardingCompleted() {
        dataStore.edit { prefs ->
            @Suppress("DEPRECATION")
            prefs[LEGACY_KEY_COMPLETED] = true
        }
    }

    /**
     * Reset all onboarding state (for "Show guide again" in About screen).
     */
    suspend fun resetOnboarding() {
        dataStore.edit { prefs ->
            prefs[KEY_SEEN_STEPS] = emptySet()
            @Suppress("DEPRECATION")
            prefs[LEGACY_KEY_COMPLETED] = false
        }
    }

    private companion object {
        val KEY_SEEN_STEPS = stringSetPreferencesKey("onboarding_seen_steps")

        // Keep the old key for migration detection
        @Suppress("DEPRECATION")
        val LEGACY_KEY_COMPLETED = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_completed")
    }
}
