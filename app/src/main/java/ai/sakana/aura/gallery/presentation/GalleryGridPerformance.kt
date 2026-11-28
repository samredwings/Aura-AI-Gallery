package ai.sakana.aura.gallery.presentation

import android.graphics.Bitmap
import android.util.LruCache
import ai.sakana.aura.gallery.domain.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A date section in the gallery, preserving the order supplied by the already-sorted UI list. */
internal data class DateMediaGroup(
    val date: LocalDate,
    val items: List<MediaItem>,
)

/**
 * Groups an already date-sorted media list in one pass. LinkedHashMap preserves both the first-seen
 * day order and the order of media within each day, so the grid does not sort the same list twice.
 */
internal fun groupMediaByDate(
    items: List<MediaItem>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<DateMediaGroup> {
    val grouped = LinkedHashMap<LocalDate, MutableList<MediaItem>>()
    items.forEach { item ->
        val date = Instant.ofEpochMilli(item.dateTakenEpochMillis).atZone(zoneId).toLocalDate()
        grouped.getOrPut(date) { mutableListOf() }.add(item)
    }
    return grouped.map { (date, dayItems) -> DateMediaGroup(date, dayItems) }
}

/**
 * A byte-bounded LRU cache for decoded video grid thumbnails. Entries are never recycled on
 * eviction because a visible Compose Image may still hold a reference to the bitmap.
 */
internal class VideoThumbnailCache(maxSizeKb: Int) {
    private val entries = object : LruCache<String, Bitmap>(maxSizeKb.also { require(it > 0) }) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            ((value.allocationByteCount.toLong() + 1023L) / 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = entries.get(key)?.takeUnless(Bitmap::isRecycled)

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) entries.put(key, bitmap)
    }
}

internal object GalleryVideoThumbnailCache {
    private const val MAX_CACHE_SIZE_KB = 24 * 1024
    private val cache = VideoThumbnailCache(MAX_CACHE_SIZE_KB)

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
}

internal fun videoThumbnailCacheKey(item: MediaItem): String =
    "${item.contentUri}|${item.dateModifiedEpochMillis}|${item.sizeBytes}"
