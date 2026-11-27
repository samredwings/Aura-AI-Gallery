package ai.sakana.aura.gallery.domain.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorCropRulesTest {
    @Test
    fun `wide image square crop is centered`() {
        assertEquals(CropBounds(left = 500, top = 0, width = 3000, height = 3000), EditorCropRules.centerCrop(4000, 3000, 1f))
    }

    @Test
    fun `portrait ratio crops the width of a landscape image`() {
        assertEquals(CropBounds(left = 800, top = 0, width = 2400, height = 3000), EditorCropRules.centerCrop(4000, 3000, 0.8f))
    }

    @Test
    fun `slightly taller target ratio crops the height`() {
        assertEquals(CropBounds(left = 0, top = 125, width = 3000, height = 3750), EditorCropRules.centerCrop(3000, 4000, 0.8f))
    }

    @Test
    fun `invalid dimensions and crop ratios are rejected`() {
        assertNull(EditorCropRules.centerCrop(0, 3000, 1f))
        assertNull(EditorCropRules.centerCrop(4000, 3000, 0f))
        assertNull(EditorCropRules.centerCrop(4000, 3000, Float.NaN))
    }

    @Test
    fun `rotation is normalized to a clockwise right angle`() {
        assertEquals(90, EditorCropRules.normalizeRotation(450))
        assertEquals(270, EditorCropRules.normalizeRotation(-90))
        assertEquals(0, EditorCropRules.normalizeRotation(720))
    }
}
