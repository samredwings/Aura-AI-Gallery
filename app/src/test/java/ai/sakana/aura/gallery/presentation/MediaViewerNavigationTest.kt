package ai.sakana.aura.gallery.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaViewerNavigationTest {
    @Test
    fun `swiping next advances one item`() {
        assertEquals(2, MediaViewerNavigation.targetIndex(currentIndex = 1, itemCount = 4, direction = 1))
    }

    @Test
    fun `swiping previous moves back one item`() {
        assertEquals(0, MediaViewerNavigation.targetIndex(currentIndex = 1, itemCount = 4, direction = -1))
    }

    @Test
    fun `navigation does not wrap past either end`() {
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = 0, itemCount = 4, direction = -1))
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = 3, itemCount = 4, direction = 1))
    }

    @Test
    fun `navigation rejects invalid positions and directions`() {
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = -1, itemCount = 4, direction = 1))
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = 4, itemCount = 4, direction = -1))
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = 0, itemCount = 0, direction = 1))
        assertNull(MediaViewerNavigation.targetIndex(currentIndex = 0, itemCount = 4, direction = 0))
    }
}
