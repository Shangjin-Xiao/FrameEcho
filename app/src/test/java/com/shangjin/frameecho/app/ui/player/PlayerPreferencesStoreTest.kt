package com.shangjin.frameecho.app.ui.player

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
class PlayerPreferencesStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: PlayerPreferencesStore

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tmpFolder.newFile("test_player_prefs.preferences_pb") }
        )
        store = PlayerPreferencesStore(dataStore)
    }

    @Test
    fun `load returns expected default values when not set`() = testScope.runTest {
        val settings = store.load()

        assertEquals(true, settings.rememberQuickSettings)
        assertEquals(false, settings.isMuted)
        assertEquals(false, settings.motionPhoto)
        assertEquals(true, settings.preserveMetadata)
    }

    @Test
    fun `setRememberQuickSettings persists value`() = testScope.runTest {
        store.setRememberQuickSettings(false)

        val settings = store.load()
        assertEquals(false, settings.rememberQuickSettings)
    }

    @Test
    fun `saveQuickSettings persists all values including format and custom uri`() = testScope.runTest {
        store.saveQuickSettings(
            isMuted = true,
            motionPhoto = true,
            preserveMetadata = false,
            format = "PNG",
            quality = 90,
            customFileName = "CustomName",
            exportDirectory = "DCIM_FRAMEECHO",
            customExportTreeUri = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        )

        val settings = store.load()
        assertEquals(true, settings.isMuted)
        assertEquals(true, settings.motionPhoto)
        assertEquals(false, settings.preserveMetadata)
        assertEquals("PNG", settings.format)
        assertEquals(90, settings.quality)
        assertEquals("CustomName", settings.customFileName)
        assertEquals("DCIM_FRAMEECHO", settings.exportDirectory)
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3APictures", settings.customExportTreeUri)
    }

    @Test
    fun `load reflects latest saved values`() = testScope.runTest {
        // Save non-default values
        store.setRememberQuickSettings(false)
        store.saveQuickSettings(isMuted = true, motionPhoto = true, preserveMetadata = false)

        val settings = store.load()
        assertEquals(false, settings.rememberQuickSettings)
        assertEquals(true, settings.isMuted)
        assertEquals(true, settings.motionPhoto)
        assertEquals(false, settings.preserveMetadata)
    }

    @Test
    fun `saveQuickSettings does not affect rememberQuickSettings`() = testScope.runTest {
        store.setRememberQuickSettings(false)
        store.saveQuickSettings(isMuted = true, motionPhoto = true, preserveMetadata = false)

        val settings = store.load()
        assertEquals(false, settings.rememberQuickSettings)
    }
}
