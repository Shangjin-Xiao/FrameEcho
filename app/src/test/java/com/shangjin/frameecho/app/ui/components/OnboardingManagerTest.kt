package com.shangjin.frameecho.app.ui.components

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var onboardingManager: OnboardingManager

    private val allStepKeys = listOf("step_welcome", "step_player_controls", "step_export_settings", "step_new_feature")
    private val legacyStepKeys = setOf("step_welcome", "step_player_controls", "step_export_settings")

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tmpFolder.newFile("test_onboarding_prefs.preferences_pb") }
        )
        onboardingManager = OnboardingManager(dataStore)
    }

    @Test
    fun `getUnseenStepKeys returns all step keys when no steps seen and legacy incomplete`() = testScope.runTest {
        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        assertEquals(allStepKeys, unseen)
    }

    @Test
    fun `getUnseenStepKeys filters out steps marked as seen`() = testScope.runTest {
        onboardingManager.markStepsSeen(setOf("step_welcome", "step_player_controls"))

        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        assertEquals(listOf("step_export_settings", "step_new_feature"), unseen)
    }

    @Test
    fun `getUnseenStepKeys returns empty list when all steps marked seen`() = testScope.runTest {
        onboardingManager.markAllSeen(allStepKeys)

        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        assertEquals(emptyList<String>(), unseen)
    }

    @Test
    fun `getUnseenStepKeys filters legacy steps when legacy onboarding completed`() = testScope.runTest {
        onboardingManager.markOnboardingCompleted()

        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        // legacyStepKeys ("step_welcome", "step_player_controls", "step_export_settings") should be treated as seen
        // Only "step_new_feature" should remain unseen
        assertEquals(listOf("step_new_feature"), unseen)
    }

    @Test
    fun `getUnseenStepKeys combines seen steps and legacy steps when legacy onboarding completed`() = testScope.runTest {
        onboardingManager.markOnboardingCompleted()
        onboardingManager.markStepsSeen(setOf("step_new_feature"))

        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        assertEquals(emptyList<String>(), unseen)
    }

    @Test
    fun `resetOnboarding clears seen steps and legacy completed state`() = testScope.runTest {
        onboardingManager.markOnboardingCompleted()
        onboardingManager.markStepsSeen(setOf("step_new_feature"))

        onboardingManager.resetOnboarding()

        val unseen = onboardingManager.getUnseenStepKeys(allStepKeys, legacyStepKeys)

        assertEquals(allStepKeys, unseen)
    }

    @Test
    fun `getUnseenStepKeys handles empty step lists and sets`() = testScope.runTest {
        val unseenWithEmptyAll = onboardingManager.getUnseenStepKeys(emptyList(), legacyStepKeys)
        assertEquals(emptyList<String>(), unseenWithEmptyAll)

        val unseenWithEmptyLegacy = onboardingManager.getUnseenStepKeys(allStepKeys, emptySet())
        assertEquals(allStepKeys, unseenWithEmptyLegacy)
    }
}
