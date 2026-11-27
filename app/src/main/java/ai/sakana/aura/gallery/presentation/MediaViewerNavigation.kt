package ai.sakana.aura.gallery.presentation

/** Returns the adjacent media index, or null when the requested swipe is out of bounds. */
internal object MediaViewerNavigation {
    fun targetIndex(currentIndex: Int, itemCount: Int, direction: Int): Int? {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount || direction !in -1..1 || direction == 0) return null
        return (currentIndex + direction).takeIf { it in 0 until itemCount }
    }
}
