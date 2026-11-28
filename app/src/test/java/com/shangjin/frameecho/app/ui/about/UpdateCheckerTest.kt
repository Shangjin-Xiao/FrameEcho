package com.shangjin.frameecho.app.ui.about

import com.shangjin.frameecho.BuildConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun isNewerVersion_comparisons_areCorrect() {
        // Newer versions
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.2", "1.2.1"))

        // Older versions
        assertFalse(UpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.1", "1.2"))

        // Same versions
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "v1.0.0"))

        // Handling suffixes
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta", "1.0.1"))
    }

    @Test
    fun inAppUpdate_flagAndFetcher_matchDistributionFlavor() = runTest {
        when (BuildConfig.FLAVOR) {
            "play" -> {
                assertFalse(BuildConfig.ENABLE_IN_APP_UPDATE)
                // Must short-circuit before making any network call
                assertNull(UpdateChecker.fetchLatestRelease())
            }
            "github" -> {
                assertTrue(BuildConfig.ENABLE_IN_APP_UPDATE)
            }
            else -> throw AssertionError("Unexpected flavor: ${BuildConfig.FLAVOR}")
        }
        assertEquals("com.shangjin.frameecho", BuildConfig.APPLICATION_ID)
    }
}
