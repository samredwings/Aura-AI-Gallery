package ai.sakana.aura.gallery.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBudgetPolicyTest {
    @Test
    fun frameRenderingDurationIsMeasuredInsteadOfVsyncCadence() {
        assertTrue(isFrameWithin16Ms(8_000_000L))
        assertTrue(isFrameWithin16Ms(16_000_000L))
        assertFalse(isFrameWithin16Ms(16_666_667L))
        assertFalse(isFrameWithin16Ms(20_000_000L))
    }

    @Test
    fun frameDeadlineUsesTheBudgetSuppliedByAndroid() {
        assertTrue(isFrameWithinDeadline(10_000_000L, 16_666_667L))
        assertFalse(isFrameWithinDeadline(17_000_000L, 16_666_667L))
        assertFalse(isFrameWithinDeadline(-1L, 16_666_667L))
        assertFalse(isFrameWithinDeadline(10_000_000L, -1L))
    }
}
