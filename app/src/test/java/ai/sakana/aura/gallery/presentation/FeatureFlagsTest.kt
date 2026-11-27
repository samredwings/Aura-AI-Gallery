package ai.sakana.aura.gallery.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {
    @Test
    fun `cloud integrations remain off while implemented video analysis is enabled`() {
        assertFalse(FeatureFlags.cloudBackupEnabled)
        assertFalse(FeatureFlags.megaEnabled)
        assertFalse(FeatureFlags.teraboxEnabled)
        assertTrue(FeatureFlags.videoFrameAnalysisEnabled)
    }
}
